package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.pedido.Pedido;
import jaider.ecommerce.pedido.PedidoRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.tienda.integracion.EnviaCredentials;
import jaider.ecommerce.tienda.integracion.TenantIntegrationResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Preparar envío" + generación de guía REAL para un pedido ya pagado — PLAN_INTEGRACION_ENVIA.md,
 * Fase 4. A diferencia de {@link EnvioCotizacionService} (Fase 3, cotización gratis en el
 * checkout), {@link #generarGuia} SÍ cobra de la cuenta de la tienda en Envia — nunca se llama
 * sin que el admin lo confirme explícitamente desde la pantalla de preparación.
 *
 * Reutiliza las columnas de seguimiento que ya existían (Pedido.transportadora/codigoRastreo/
 * linkSeguimiento — hasta ahora las llenaba el admin a mano); se llenan solas al generar la guía.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvioGuiaService {

    private final TenantSupport tenantSupport;
    private final PedidoRepository pedidoRepo;
    private final TiendaRepository tiendaRepo;
    private final EnvioCotizacionService cotizacionService;
    private final TenantIntegrationResolver integrationResolver;
    private final PaqueteCalculoService paqueteCalculoService;
    private final EnviaGeocodesClient geocodesClient;
    private final EnviaRateClient rateClient;
    private final EnviaLabelClient labelClient;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public PrepararEnvioResponse preparar(Long tndId, Long pedidoId) {
        tenantSupport.requireTenant(em);
        Pedido pedido = pedidoObligatorio(tndId, pedidoId);
        Tienda tienda = tiendaEnModoEnvia(tndId);

        List<PaqueteCalculado> paquetes = paquetesDelPedido(pedido);
        DireccionEnvia destino = direccionDesdeSnapshot(pedido);
        DireccionEnvia origen = cotizacionService.cargarDireccionOrigen(tndId);
        GeocodeResultado origenGeo = geocodesClient.resolver(origen.codigoPostal());
        GeocodeResultado destinoGeo = geocodesClient.resolver(destino.codigoPostal());
        EnviaCredentials creds = integrationResolver.envioCredentials(tndId);
        String host = rateClient.hostPara(tienda.getEnviaAmbiente());
        long declaradoCop = pedido.getSubtotalCentavos() / 100L;

        List<CotizacionCarrier> cotizaciones = new ArrayList<>();
        for (String carrier : cotizacionService.ordenTransportadoras(tndId)) {
            Optional<CotizacionCarrier> cot = rateClient.cotizar(host, creds.apiToken(), carrier,
                    origen, origenGeo, destino, destinoGeo, paquetes, declaradoCop);
            cot.ifPresent(cotizaciones::add);
        }

        return new PrepararEnvioResponse(paquetes, cotizaciones,
                pedido.getEnviaShipmentId() != null,
                pedido.getTransportadora(), pedido.getCodigoRastreo(), pedido.getEnviaGuiaUrl(),
                pedido.getEnviaShipmentId(), pedido.getEnviaCostoRealCentavos());
    }

    /** Crea el envío REAL y lo cobra de la cuenta de Envia de la tienda — protegido contra doble
     *  clic con una reserva atómica (ver PedidoRepository.reservarParaGuiaEnvia — corrección de
     *  auditoría 2026-09-01: la validación anterior, leer y RECIÉN DESPUÉS escribir, dejaba una
     *  ventana real donde dos solicitudes concurrentes generaban y cobraban dos guías por el
     *  mismo pedido). Un pedido con guía ya generada no puede volver a generar otra sin antes
     *  cancelarla (cancelación todavía no está construida — Fase futura). */
    @Transactional
    public GuiaGenerada generarGuia(Long tndId, Long pedidoId, GenerarGuiaRequest req, Long adminId) {
        tenantSupport.requireTenant(em);
        Pedido pedido = pedidoObligatorio(tndId, pedidoId);
        Tienda tienda = tiendaEnModoEnvia(tndId);

        if (req.carrier() == null || req.carrier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar la transportadora");
        }
        // Corrección de auditoría: antes se aceptaba cualquier texto como carrier — ahora se
        // valida contra los mismos 4 carriers reales que el resto de la integración reconoce
        // (TransportadoraService.CARRIERS_VALIDOS), para no gastar una llamada real con un valor
        // que Envia de todos modos va a rechazar, o peor, con uno mal escrito que Envia acepte de
        // forma ambigua.
        if (!TransportadoraService.CARRIERS_VALIDOS.contains(req.carrier().trim().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transportadora inválida: " + req.carrier() + " (válidas: " + TransportadoraService.CARRIERS_VALIDOS + ")");
        }

        int reservado = pedidoRepo.reservarParaGuiaEnvia(pedido.getId());
        if (reservado == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este pedido ya tiene una guía generada o en proceso — no se puede generar otra");
        }

        List<PaqueteCalculado> paquetes = paquetesDelPedido(pedido);
        DireccionEnvia destino = direccionDesdeSnapshot(pedido);
        DireccionEnvia origen = cotizacionService.cargarDireccionOrigen(tndId);
        GeocodeResultado origenGeo = geocodesClient.resolver(origen.codigoPostal());
        GeocodeResultado destinoGeo = geocodesClient.resolver(destino.codigoPostal());
        EnviaCredentials creds = integrationResolver.envioCredentials(tndId);
        String host = rateClient.hostPara(tienda.getEnviaAmbiente());
        long declaradoCop = pedido.getSubtotalCentavos() / 100L;

        GuiaGenerada guia;
        try {
            guia = labelClient.generar(host, creds.apiToken(), req.carrier(), req.servicio(),
                    origen, origenGeo, destino, destinoGeo, paquetes, declaradoCop, pedido.getNumero());
            // Se loguea ACÁ, apenas Envia confirma el envío — ya se cobró de verdad y existe en
            // el sistema de Envia aunque el guardado en nuestra BD falle después (idempotencia:
            // sin esto, un fallo de BD tras un cobro real dejaría el shipment "huérfano", sin
            // ningún rastro en nuestro lado para reconciliar a mano).
            log.info("[EnvioGuia] Envia confirmó el envío — pedido={} tenant={} carrier={} shipmentId={} tracking={}",
                    pedidoId, tndId, guia.carrier(), guia.shipmentId(), guia.trackingNumber());
        } catch (Exception e) {
            // Libera la reserva: Envia NO confirmó ningún envío, así que este pedido debe poder
            // reintentar limpio — si no se libera, quedaría bloqueado con 'RESERVANDO' para
            // siempre (indistinguible de una guía real generada).
            pedidoRepo.liberarReservaGuiaEnvia(pedido.getId());
            log.error("[EnvioGuia] falló generar guía real para pedido {} (tenant {}): {}", pedidoId, tndId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Envia no pudo generar la guía: " + e.getMessage());
        }

        // UPDATE nativo explícito, no repo.save() — ver el javadoc de
        // PedidoRepository.registrarGuiaEnvia() (repo.save() reescribiría ped_estado, un enum
        // nativo de Postgres que Hibernate no puede bindear como varchar sin CAST).
        pedidoRepo.registrarGuiaEnvia(pedido.getId(), guia.carrier(), guia.trackingNumber(),
                guia.trackUrl(), "ambos", guia.shipmentId(), guia.labelUrl(), guia.totalPriceCop() * 100L);

        log.info("[EnvioGuia] guía real generada — pedido={} tenant={} carrier={} tracking={} admin={}",
                pedidoId, tndId, guia.carrier(), guia.trackingNumber(), adminId);
        return guia;
    }

    private Tienda tiendaEnModoEnvia(Long tndId) {
        Tienda tienda = tiendaRepo.findById(tndId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
        if (!"envia".equals(tienda.getEnvioModo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta tienda no calcula el envío con Envia (modo actual: " + tienda.getEnvioModo() + ")");
        }
        return tienda;
    }

    private static final java.util.Set<String> ESTADOS_VALIDOS_PARA_ENVIO =
            java.util.Set.of("pagado", "preparando", "enviado", "entregado");

    private Pedido pedidoObligatorio(Long tndId, Long pedidoId) {
        Pedido pedido = pedidoRepo.findById(pedidoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
        if (!tndId.equals(pedido.getTndId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }
        // Un pedido pendiente de pago, cancelado o devuelto no debe generar un envío real.
        if (!ESTADOS_VALIDOS_PARA_ENVIO.contains(pedido.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este pedido está en estado \"" + pedido.getEstado() + "\" — no se puede preparar su envío");
        }
        return pedido;
    }

    /** Corrección de auditoría (2026-09-01): usa los paquetes CONGELADOS en el checkout
     *  ({@code pedido.getEnvioCotizacionSnapshot()}) en vez de recalcularlos desde el producto/
     *  empaque ACTUALES — si el admin cambia o borra un empaque después de la compra, la guía
     *  real ya no debe verse afectada. Los pedidos creados ANTES de este fix no tienen ese
     *  snapshot todavía (era null) — para esos, y solo para esos, se recalcula como antes
     *  (con una advertencia en el log, para poder identificarlos). */
    @SuppressWarnings("unchecked")
    private List<PaqueteCalculado> paquetesDelPedido(Pedido pedido) {
        Map<String, Object> snapshot = pedido.getEnvioCotizacionSnapshot();
        Object paquetesRaw = snapshot != null ? snapshot.get("paquetes") : null;
        if (!(paquetesRaw instanceof List<?> lista) || lista.isEmpty()) {
            log.warn("[EnvioGuia] pedido={} sin paquetes congelados (pedido anterior a esta corrección) — recalculando desde el catálogo actual",
                    pedido.getId());
            return paqueteCalculoService.calcular(itemsDelPedido(pedido.getId()));
        }
        return lista.stream()
                .map(o -> (Map<String, Object>) o)
                .map(this::mapaAPaquete)
                .toList();
    }

    private PaqueteCalculado mapaAPaquete(Map<String, Object> m) {
        return new PaqueteCalculado(
                ((Number) m.get("empaque_id")).longValue(),
                (String) m.get("empaque_nombre"),
                ((Number) m.get("cantidad")).intValue(),
                ((Number) m.get("peso_gramos_por_unidad")).intValue(),
                ((Number) m.get("largo_cm")).shortValue(),
                ((Number) m.get("ancho_cm")).shortValue(),
                ((Number) m.get("alto_cm")).shortValue()
        );
    }

    @SuppressWarnings("unchecked")
    private List<ItemParaPaquete> itemsDelPedido(Long pedidoId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT pi_prd_id, pi_cantidad FROM pedido_items
                WHERE pi_ped_id = :pedidoId AND pi_prd_id IS NOT NULL
                """)
                .setParameter("pedidoId", pedidoId)
                .getResultList();
        return rows.stream()
                .map(r -> new ItemParaPaquete(((Number) r[0]).longValue(), ((Number) r[1]).intValue()))
                .toList();
    }

    private DireccionEnvia direccionDesdeSnapshot(Pedido pedido) {
        Map<String, Object> dir = pedido.getDirSnapshot();
        String codigoPostal = str(dir.get("codigo_postal"));
        if (codigoPostal == null || codigoPostal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este pedido no tiene código postal en su dirección — no se puede calcular el envío real");
        }
        return new DireccionEnvia(str(dir.get("contacto_nombre")), str(dir.get("contacto_telefono")),
                str(dir.get("direccion")), str(dir.get("municipio")), str(dir.get("departamento")), codigoPostal);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
