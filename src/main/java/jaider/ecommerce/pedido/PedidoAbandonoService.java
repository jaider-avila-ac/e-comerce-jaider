package jaider.ecommerce.pedido;

import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Trabajo transaccional de cancelación de pedidos abandonados (ver PedidoAbandonoScheduler).
 * Bean separado del scheduler para que estos métodos @Transactional se invoquen siempre a
 * través del proxy de Spring (mismo motivo que PedidoAutoConfirmacionService).
 */
@Service
@RequiredArgsConstructor
public class PedidoAbandonoService {

    private final TenantSupport tenantSupport;
    private final PedidoService pedidoService;

    @PersistenceContext
    private EntityManager em;

    // Si el checkout se abandona (el cliente cierra la pestaña sin pagar, o la transacción con
    // Wompi nunca se completa), el pedido queda en "pendiente_pago" para siempre — no hay ningún
    // otro mecanismo que lo resuelva (ver PagoWebhookService/PagoConfirmacionService, que solo
    // actúan si Wompi manda un webhook). Pasadas estas horas sin confirmación, se cancela.
    private static final int HORAS_LIMITE = 2;

    @Transactional(readOnly = true)
    public List<Long> tenantsActivos() {
        @SuppressWarnings("unchecked")
        List<Number> ids = em.createNativeQuery("SELECT tnd_id FROM tiendas WHERE tnd_activo = true").getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> pedidosAbandonados(Long tndId) {
        tenantSupport.applyTenant(em);
        @SuppressWarnings("unchecked")
        List<Number> ids = em.createNativeQuery("""
                SELECT ped_id FROM pedidos
                WHERE ped_estado = 'pendiente_pago'
                  AND ped_creado_en <= now() - make_interval(hours => :horas)
                """)
                .setParameter("horas", HORAS_LIMITE)
                .getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    /**
     * Nunca hubo pago aprobado (no hay reembolso que hacer) ni stock descontado (no hay que
     * restaurarlo) — cancelarPorAdmin() ya maneja ambos casos correctamente cuando pagId/adminId
     * son null. Antes de cancelar se vuelve a leer el estado: si el webhook de Wompi confirmó el
     * pago justo entre la consulta de pedidosAbandonados() y esta llamada, el pedido ya no está
     * en pendiente_pago y no se toca — evita cancelar por error un pedido que se acaba de pagar.
     */
    @Transactional
    public void cancelarAbandonado(Long pedId) {
        tenantSupport.applyTenant(em);
        String estadoActual = (String) em.createNativeQuery(
                "SELECT ped_estado::text FROM pedidos WHERE ped_id = :id")
                .setParameter("id", pedId)
                .getSingleResult();
        if (!"pendiente_pago".equals(estadoActual)) {
            return;
        }
        pedidoService.cancelarPorAdmin(pedId, "pago_no_confirmado", null,
                "El pago no se confirmó dentro de " + HORAS_LIMITE + " horas", null);
    }
}
