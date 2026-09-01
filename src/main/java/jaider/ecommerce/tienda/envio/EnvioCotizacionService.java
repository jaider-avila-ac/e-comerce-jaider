package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.sucursal.Sucursal;
import jaider.ecommerce.sucursal.SucursalRepository;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.tienda.integracion.EnviaCredentials;
import jaider.ecommerce.tienda.integracion.TenantIntegrationResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Calcula el precio de envío real para el carrito de un cliente — PLAN_INTEGRACION_ENVIA.md,
 * Fase 3. Solo aplica a tiendas en modo 'envia'; las demás usan su propio cálculo (contra
 * entrega / fijo, ya resuelto en {@code PedidoCreacionService}).
 *
 * Garantía de negocio pedida por el usuario: "al cliente sí o sí se le debe dar su precio de
 * envío" — por eso, si TODOS los carriers configurados fallan (sin servicio, error de red,
 * etc.), se cae al costo fijo de la tienda ({@code tnd_envio_costo_centavos}) marcado como
 * {@code estimado=true} en vez de devolver un error al cliente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvioCotizacionService {

    /** Orden por defecto si la tienda no configuró nada en `tienda_transportadoras` — el pedido
     *  original del usuario: Servientrega primero, Envia (Envía Colombia) como respaldo, y las
     *  demás transportadoras confirmadas cotizando después. El admin puede cambiar este orden. */
    private static final List<String> ORDEN_POR_DEFECTO =
            List.of("servientrega", "envia", "coordinadora", "interrapidisimo");

    private final TenantSupport tenantSupport;
    private final TiendaRepository tiendaRepo;
    private final SucursalRepository sucursalRepo;
    private final TiendaTransportadoraRepository transportadoraRepo;
    private final TenantIntegrationResolver integrationResolver;
    private final PaqueteCalculoService paqueteCalculoService;
    private final EnviaGeocodesClient geocodesClient;
    private final EnviaRateClient rateClient;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public EnvioCotizacionResponse cotizar(Long usrId, Long tndId, Long direccionId) {
        return cotizarParaCongelar(usrId, tndId, direccionId).respuesta();
    }

    /** Igual que {@link #cotizar}, pero además devuelve los paquetes usados — para que el
     *  checkout pueda CONGELARLOS en el pedido (ver {@code Pedido.envioCotizacionSnapshot} y la
     *  auditoría 2026-09-01: sin esto, generar la guía real más tarde recalculaba el paquete
     *  desde el producto/empaque ACTUALES, que pudieron cambiar desde la compra). */
    @Transactional(readOnly = true)
    public CotizacionParaCongelar cotizarParaCongelar(Long usrId, Long tndId, Long direccionId) {
        tenantSupport.requireTenant(em);

        Tienda tienda = tiendaRepo.findById(tndId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
        if (!"envia".equals(tienda.getEnvioModo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta tienda no calcula el envío con Envia (modo actual: " + tienda.getEnvioModo() + ")");
        }

        List<ItemParaPaquete> items = cargarCarrito(usrId);
        List<PaqueteCalculado> paquetes = paqueteCalculoService.calcular(items);
        long declaradoCop = subtotalCarrito(usrId) / 100L;

        DireccionEnvia destino = cargarDireccionDestino(usrId, tndId, direccionId);
        DireccionEnvia origen = cargarDireccionOrigen(tndId);

        GeocodeResultado origenGeo = geocodesClient.resolver(origen.codigoPostal());
        GeocodeResultado destinoGeo = geocodesClient.resolver(destino.codigoPostal());

        EnviaCredentials creds = integrationResolver.envioCredentials(tndId);
        String host = rateClient.hostPara(tienda.getEnviaAmbiente());

        for (String carrier : ordenTransportadoras(tndId)) {
            Optional<CotizacionCarrier> cot = rateClient.cotizar(host, creds.apiToken(), carrier,
                    origen, origenGeo, destino, destinoGeo, paquetes, declaradoCop);
            if (cot.isPresent()) {
                CotizacionCarrier c = cot.get();
                EnvioCotizacionResponse resp = new EnvioCotizacionResponse(c.precioCop() * 100L, c.carrier(),
                        c.servicioDescripcion(), c.servicioCodigo(), c.tiempoEstimado(), false);
                return new CotizacionParaCongelar(resp, paquetes);
            }
        }

        // Ningún carrier respondió — respaldo garantizado, nunca se deja al cliente sin precio.
        log.warn("[EnvioCotizacion] ningún carrier cotizó para tenant={}, usando costo fijo de respaldo", tndId);
        EnvioCotizacionResponse resp = new EnvioCotizacionResponse(tienda.getEnvioCostoCentavos(), "estimado",
                "Envío estándar", null, "3-5 días hábiles", true);
        return new CotizacionParaCongelar(resp, paquetes);
    }

    List<String> ordenTransportadoras(Long tndId) {
        List<TiendaTransportadora> configuradas = transportadoraRepo.findAllByActivoTrueOrderByOrdenAscCarrierAsc();
        if (configuradas.isEmpty()) return ORDEN_POR_DEFECTO;
        return configuradas.stream().map(TiendaTransportadora::getCarrier).toList();
    }

    private List<ItemParaPaquete> cargarCarrito(Long usrId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT ci.ci_prd_id, ci.ci_cantidad
                FROM carrito_items ci
                JOIN carritos c ON c.car_id = ci.ci_car_id
                WHERE c.car_usr_id = :usrId
                """)
                .setParameter("usrId", usrId)
                .getResultList();
        return rows.stream()
                .map(r -> new ItemParaPaquete(((Number) r[0]).longValue(), ((Number) r[1]).intValue()))
                .toList();
    }

    private long subtotalCarrito(Long usrId) {
        Number total = (Number) em.createNativeQuery("""
                SELECT COALESCE(SUM(ci.ci_cantidad * ci.ci_precio_snap_centavos), 0)
                FROM carrito_items ci
                JOIN carritos c ON c.car_id = ci.ci_car_id
                WHERE c.car_usr_id = :usrId
                """)
                .setParameter("usrId", usrId)
                .getSingleResult();
        return total.longValue();
    }

    private DireccionEnvia cargarDireccionDestino(Long usrId, Long tndId, Long direccionId) {
        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery("""
                    SELECT cd_direccion, cd_contacto_nombre, cd_contacto_telefono, cd_municipio,
                           cd_departamento, cd_codigo_postal
                    FROM clientes_direcciones
                    WHERE cd_id = :id AND cd_usr_id = :usrId AND cd_tnd_id = :tndId
                    """)
                    .setParameter("id", direccionId)
                    .setParameter("usrId", usrId)
                    .setParameter("tndId", tndId)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dirección no encontrada");
        }
        String codigoPostal = (String) row[5];
        if (codigoPostal == null || codigoPostal.isBlank()) {
            // No debería pasar: TiendaClientePerfilService ya lo exige para tiendas en modo
            // 'envia' — pero una dirección guardada ANTES de que la tienda activara ese modo
            // podría no tenerlo, así que se revalida acá también.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta dirección no tiene código postal — actualízala antes de continuar");
        }
        return new DireccionEnvia((String) row[1], (String) row[2], (String) row[0],
                (String) row[3], (String) row[4], codigoPostal);
    }

    DireccionEnvia cargarDireccionOrigen(Long tndId) {
        Sucursal sucursal = sucursalRepo.findByActivoTrueOrderByNombreAsc().stream()
                .filter(this::tieneOrigenCompleto)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ninguna sucursal activa tiene una dirección de origen completa"));
        return new DireccionEnvia(sucursal.getEnvioOrigenNombre(), sucursal.getEnvioOrigenTelefono(),
                sucursal.getEnvioOrigenDireccion(), sucursal.getEnvioOrigenMunicipio(),
                sucursal.getEnvioOrigenDepartamento(), sucursal.getEnvioOrigenCodigoPostal());
    }

    private boolean tieneOrigenCompleto(Sucursal s) {
        return noBlank(s.getEnvioOrigenNombre()) && noBlank(s.getEnvioOrigenTelefono())
                && noBlank(s.getEnvioOrigenDireccion()) && noBlank(s.getEnvioOrigenMunicipio())
                && noBlank(s.getEnvioOrigenDepartamento()) && noBlank(s.getEnvioOrigenCodigoPostal());
    }

    private boolean noBlank(String v) {
        return v != null && !v.isBlank();
    }
}
