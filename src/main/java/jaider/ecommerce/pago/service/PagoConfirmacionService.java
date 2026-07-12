package jaider.ecommerce.pago.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jaider.ecommerce.notificacion.event.AlertaStockEvent;
import jaider.ecommerce.notificacion.event.PedidoPagadoEvent;
import jaider.ecommerce.pedido.PedidoRepository;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aplica los efectos de un pago resuelto (pedido/carrito/inventario).
 *
 * registrarAprobado() es deliberadamente mínimo: solo marca el pago como APPROVED, para que
 * quede registrado incluso si confirmarPedido() (descuento de stock, etc.) falla después.
 *
 * Cada método usa @Transactional simple (no REQUIRES_NEW): cuando el llamador (webhook) ya está
 * en una transacción, estos métodos se unen a ella. Cuando el llamador NO está en una transacción
 * (el cobro directo con tarjeta, ver PedidoCheckoutService.pagarConTarjeta — deliberadamente sin
 * @Transactional porque cobra vía HTTP antes de tocar la BD), cada llamada abre y confirma su
 * propia transacción de forma independiente, logrando el mismo efecto de "fase 1 sobrevive aunque
 * fase 2 falle" sin necesidad de REQUIRES_NEW.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PagoConfirmacionService {

    private static final Set<String> METODOS_VALIDOS =
            Set.of("CARD", "NEQUI", "PSE", "BANCOLOMBIA_TRANSFER", "EFECTIVO", "OTRO");

    private final TenantSupport tenantSupport;
    private final PedidoRepository pedidoRepo;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void registrarAprobado(Long pagoId, String gatewayTxId, String metodo, Map<String, Object> respuestaJson) {
        tenantSupport.applyTenant(em);
        em.createNativeQuery("""
                UPDATE pagos SET pag_estado = CAST('APPROVED' AS estado_pago),
                    pag_gateway_tx_id = :txId,
                    pag_metodo = CAST(:metodo AS metodo_pago),
                    pag_respuesta_json = CAST(:json AS jsonb)
                WHERE pag_id = :id
                """)
                .setParameter("txId", gatewayTxId)
                .setParameter("metodo", sanitizeMetodo(metodo))
                .setParameter("json", toJson(respuestaJson))
                .setParameter("id", pagoId)
                .executeUpdate();
    }

    @Transactional
    public void registrarRechazado(Long pagoId, String estado, String motivo, String gatewayTxId,
                                    String metodo, Map<String, Object> respuestaJson) {
        tenantSupport.applyTenant(em);
        em.createNativeQuery("""
                UPDATE pagos SET pag_estado = CAST(:estado AS estado_pago),
                    pag_motivo_rechazo = :motivo,
                    pag_gateway_tx_id = COALESCE(:txId, pag_gateway_tx_id),
                    pag_metodo = COALESCE(CAST(:metodo AS metodo_pago), pag_metodo),
                    pag_respuesta_json = CAST(:json AS jsonb)
                WHERE pag_id = :id
                """)
                .setParameter("estado", estado)
                .setParameter("motivo", motivo)
                .setParameter("txId", gatewayTxId)
                .setParameter("metodo", sanitizeMetodo(metodo))
                .setParameter("json", toJson(respuestaJson))
                .setParameter("id", pagoId)
                .executeUpdate();
    }

    @Transactional
    public void confirmarPedido(Long pagoId) {
        tenantSupport.applyTenant(em);

        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT p.pag_ped_id, p.pag_usr_id, ped.ped_estado::text, ped.ped_numero, ped.ped_tnd_id
                FROM pagos p JOIN pedidos ped ON ped.ped_id = p.pag_ped_id
                WHERE p.pag_id = :id
                """)
                .setParameter("id", pagoId)
                .getSingleResult();

        Long pedId = ((Number) row[0]).longValue();
        Long usrId = ((Number) row[1]).longValue();
        String estadoActual = (String) row[2];
        String numero = (String) row[3];
        Long tndId = ((Number) row[4]).longValue();

        if (!"pendiente_pago".equals(estadoActual)) {
            log.info("[Confirmación] Pedido {} ya estaba pagado — ignorado", pedId);
            return;
        }

        pedidoRepo.updateEstado(pedId, "pagado");
        em.createNativeQuery("""
                INSERT INTO pedido_historial_estados (phe_ped_id, phe_estado)
                VALUES (:pedId, CAST('pagado' AS estado_pedido))
                """)
                .setParameter("pedId", pedId)
                .executeUpdate();

        boolean huboFaltante = descontarStock(pedId);
        limpiarCarrito(usrId);
        log.info("[Confirmación] Pedido {} confirmado como pagado", pedId);

        // Canal lateral: la notificación se publica recién después de que esta transacción haga
        // commit (ver NotificacionEventListener), así que nunca puede afectar este flujo de pago.
        eventPublisher.publishEvent(new PedidoPagadoEvent(tndId, pedId, numero));
        if (huboFaltante) {
            eventPublisher.publishEvent(new AlertaStockEvent(tndId, pedId, numero));
        }
    }

    @Transactional
    public void cancelarPedidoNoAprobado(Long pagoId) {
        tenantSupport.applyTenant(em);

        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT p.pag_ped_id, ped.ped_estado::text
                FROM pagos p JOIN pedidos ped ON ped.ped_id = p.pag_ped_id
                WHERE p.pag_id = :id
                """)
                .setParameter("id", pagoId)
                .getSingleResult();

        Long pedId = ((Number) row[0]).longValue();
        String estadoActual = (String) row[1];

        if (!"pendiente_pago".equals(estadoActual)) {
            return;
        }

        pedidoRepo.updateEstado(pedId, "cancelado");
        em.createNativeQuery("""
                INSERT INTO pedido_historial_estados (phe_ped_id, phe_estado)
                VALUES (:pedId, CAST('cancelado' AS estado_pedido))
                """)
                .setParameter("pedId", pedId)
                .executeUpdate();
    }

    private boolean descontarStock(Long pedId) {
        em.createNativeQuery("SELECT set_config('app.current_ped_id', :pedId, true)")
                .setParameter("pedId", pedId.toString())
                .getSingleResult();

        @SuppressWarnings("unchecked")
        List<Object[]> items = em.createNativeQuery("""
                SELECT pi_id, pi_var_id, pi_cantidad FROM pedido_items
                WHERE pi_ped_id = :pedId AND pi_var_id IS NOT NULL
                """)
                .setParameter("pedId", pedId)
                .getResultList();

        boolean huboFaltante = false;

        for (Object[] item : items) {
            Long itemId = ((Number) item[0]).longValue();
            Long varId = ((Number) item[1]).longValue();
            int cantidad = ((Number) item[2]).intValue();

            int updated = em.createNativeQuery("""
                    UPDATE variantes SET var_stock = var_stock - :cantidad
                    WHERE var_id = :varId AND var_stock >= :cantidad
                    """)
                    .setParameter("cantidad", cantidad)
                    .setParameter("varId", varId)
                    .executeUpdate();

            if (updated == 0) {
                log.error("[Stock] Variante {} sin stock suficiente al confirmar pedido {} — pago ya aprobado, " +
                        "se marca el pedido para revisión manual del admin", varId, pedId);
                em.createNativeQuery("UPDATE pedido_items SET pi_stock_insuficiente = true WHERE pi_id = :itemId")
                        .setParameter("itemId", itemId)
                        .executeUpdate();
                huboFaltante = true;
            }
        }

        if (huboFaltante) {
            em.createNativeQuery("UPDATE pedidos SET ped_alerta_stock = true WHERE ped_id = :pedId")
                    .setParameter("pedId", pedId)
                    .executeUpdate();
        }

        return huboFaltante;
    }

    private void limpiarCarrito(Long usrId) {
        em.createNativeQuery("""
                DELETE FROM carrito_items
                WHERE ci_car_id = (SELECT car_id FROM carritos WHERE car_usr_id = :usrId)
                """)
                .setParameter("usrId", usrId)
                .executeUpdate();
    }

    private String sanitizeMetodo(String metodo) {
        if (metodo == null) return null;
        return METODOS_VALIDOS.contains(metodo) ? metodo : "OTRO";
    }

    private String toJson(Map<String, Object> data) {
        if (data == null) return "{}";
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("No se pudo serializar respuesta_json: {}", e.getMessage());
            return "{}";
        }
    }
}
