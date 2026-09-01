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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Todo el acceso a datos (lectura Y escritura) de "preparar envío" / "generar guía real" —
 * PLAN_INTEGRACION_ENVIA.md, Fase 4. Bean SEPARADO de {@link EnvioGuiaService} exactamente por
 * el mismo motivo que {@code PedidoCreacionService} está separado de {@code PedidoCheckoutService}:
 * @Transactional es un proxy AOP — una auto-invocación (this.metodo()) lo saltaría por completo.
 * {@link EnvioGuiaService} ya NO es @Transactional a nivel de método, así que cada paso de acá se
 * invoca desde ahí como una llamada normal entre beans, pasando de verdad por el proxy.
 *
 * Corrección de auditoría (2026-09-01, tercera vuelta — CRÍTICO): antes, la reserva, la llamada
 * real a Envia (que YA cobra) y el registro final vivían en LA MISMA transacción de
 * {@code generarGuia()} — si el commit final fallaba por cualquier motivo, Postgres revertía
 * TODO, incluida la reserva, dejando el pedido como si nunca se hubiera generado nada aunque Envia
 * ya hubiera cobrado de verdad (un reintento generaba una SEGUNDA guía real). Ahora: reservar(),
 * confirmarShipmentIdTx() y registrarDetalleGuia() son transacciones {@code REQUIRES_NEW}
 * independientes, y la llamada real a Envia ({@link EnviaLabelClient#generar}) ocurre en
 * {@link EnvioGuiaService#generarGuia} completamente FUERA de cualquier transacción abierta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvioGuiaTransaccionesService {

    private static final java.util.Set<String> ESTADOS_VALIDOS_PARA_ENVIO =
            java.util.Set.of("pagado", "preparando", "enviado");

    private final TenantSupport tenantSupport;
    private final PedidoRepository pedidoRepo;
    private final TiendaRepository tiendaRepo;
    private final EnvioCotizacionService cotizacionService;
    private final TenantIntegrationResolver integrationResolver;
    private final PaqueteCalculoService paqueteCalculoService;
    private final EnviaRateClient rateClient;

    @PersistenceContext
    private EntityManager em;

    /** Todo lo que "preparar envío"/"generar guía" necesitan de nuestra propia BD — pedido,
     *  tienda, paquetes congelados, direcciones de origen/destino, credenciales y host. Ninguna
     *  llamada externa (geocoding, Envia) ocurre acá — esas siguen siendo responsabilidad de
     *  {@link EnvioGuiaService}, fuera de esta transacción de solo lectura. */
    public record DatosGuia(Pedido pedido, Tienda tienda, List<PaqueteCalculado> paquetes,
                             DireccionEnvia destino, DireccionEnvia origen,
                             EnviaCredentials creds, String host, long declaradoCop) {}

    @Transactional(readOnly = true)
    public DatosGuia cargarDatosParaGuia(Long tndId, Long pedidoId) {
        tenantSupport.requireTenant(em);
        Pedido pedido = pedidoObligatorio(tndId, pedidoId);
        Tienda tienda = tiendaEnModoEnvia(tndId);

        List<PaqueteCalculado> paquetes = paquetesDelPedido(pedido);
        DireccionEnvia destino = direccionDesdeSnapshot(pedido);
        DireccionEnvia origen = cotizacionService.cargarDireccionOrigen(tndId);
        EnviaCredentials creds = integrationResolver.envioCredentials(tndId);
        String host = rateClient.hostPara(tienda.getEnviaAmbiente());
        long declaradoCop = pedido.getSubtotalCentavos() / 100L;

        return new DatosGuia(pedido, tienda, paquetes, destino, origen, creds, host, declaradoCop);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reservar(Long pedidoId) {
        tenantSupport.requireTenant(em);
        return pedidoRepo.reservarParaGuiaEnvia(pedidoId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void liberarReserva(Long pedidoId) {
        tenantSupport.requireTenant(em);
        pedidoRepo.liberarReservaGuiaEnvia(pedidoId);
    }

    /** Paso crítico: persiste el shipmentId REAL apenas Envia lo confirma. A partir de este
     *  punto ya hay un cobro real — {@link EnvioGuiaService} reintenta este método varias veces
     *  ante un fallo transitorio (cada intento pasa por el proxy porque es una llamada entre
     *  beans), porque renunciar sin más dejaría el pedido bloqueado en 'RESERVANDO' sin ningún
     *  rastro persistido del cobro real que sí ocurrió. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int confirmarShipmentIdTx(Long pedidoId, String shipmentId) {
        tenantSupport.requireTenant(em);
        return pedidoRepo.confirmarShipmentIdGuiaEnvia(pedidoId, shipmentId);
    }

    /** Paso final (best-effort): los campos descriptivos. Si esto falla, el shipmentId real ya
     *  quedó persistido en el paso anterior (bloqueando cualquier duplicado) — solo faltarían
     *  tracking/PDF/costo visibles en el panel, reconciliables a mano con el shipmentId del log. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarDetalleGuia(Long pedidoId, Long tndId, GuiaGenerada guia, String ambiente) {
        tenantSupport.requireTenant(em);
        try {
            int filas = pedidoRepo.registrarGuiaEnvia(pedidoId, guia.carrier(), guia.trackingNumber(),
                    guia.trackUrl(), "ambos", guia.shipmentId(), guia.labelUrl(), guia.totalPriceCop() * 100L, ambiente);
            if (filas == 0) {
                log.error("[EnvioGuia] no se pudo registrar el detalle de la guía para pedido={} tenant={} " +
                        "(shipmentId={} ya no coincidía) — el shipmentId real sigue persistido, solo faltan los campos descriptivos.",
                        pedidoId, tndId, guia.shipmentId());
            }
        } catch (Exception e) {
            log.error("[EnvioGuia] falló registrar el detalle (tracking/PDF/costo) de la guía ya confirmada — " +
                            "pedido={} tenant={} shipmentId={}: {} — el shipmentId real YA está persistido, " +
                            "esto solo afecta los campos descriptivos, reconciliables a mano.",
                    pedidoId, tndId, guia.shipmentId(), e.getMessage());
        }
    }

    // ── Lo que antes vivía en EnvioGuiaService (movido acá para que cargarDatosParaGuia lo
    //    pueda usar dentro de su propia transacción) ─────────────────────────────────────────

    private Tienda tiendaEnModoEnvia(Long tndId) {
        Tienda tienda = tiendaRepo.findById(tndId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
        if (!"envia".equals(tienda.getEnvioModo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta tienda no calcula el envío con Envia (modo actual: " + tienda.getEnvioModo() + ")");
        }
        return tienda;
    }

    private Pedido pedidoObligatorio(Long tndId, Long pedidoId) {
        Pedido pedido = pedidoRepo.findById(pedidoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
        if (!tndId.equals(pedido.getTndId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }
        // Un pedido pendiente de pago, cancelado, devuelto o YA ENTREGADO no debe generar un
        // envío real nuevo — corrección de auditoría (2026-09-01, tercera vuelta): "entregado"
        // se sacó de la lista, porque un pedido ya entregado (típicamente sin necesitar guía
        // nueva) podía disparar un cobro real que ya no tiene sentido de negocio.
        if (!ESTADOS_VALIDOS_PARA_ENVIO.contains(pedido.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este pedido está en estado \"" + pedido.getEstado() + "\" — no se puede preparar su envío");
        }
        return pedido;
    }

    /** Usa los paquetes CONGELADOS en el checkout ({@code pedido.getEnvioCotizacionSnapshot()})
     *  en vez de recalcularlos desde el producto/empaque ACTUALES — si el admin cambia o borra un
     *  empaque después de la compra, la guía real ya no debe verse afectada. Los pedidos creados
     *  ANTES de este fix no tienen ese snapshot todavía (era null) — para esos, y solo para esos,
     *  se recalcula como antes (con una advertencia en el log). */
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
