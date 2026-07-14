package jaider.ecommerce.pedido;

import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Trabajo transaccional del auto-confirmado de entrega (ver PedidoAutoConfirmacionScheduler).
 * Bean separado del scheduler para que este método @Transactional se invoque siempre a
 * través del proxy de Spring (mismo motivo que PedidoCreacionService/PedidoCheckoutService).
 */
@Service
@RequiredArgsConstructor
public class PedidoAutoConfirmacionService {

    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<Long> tenantsActivos() {
        @SuppressWarnings("unchecked")
        List<Number> ids = em.createNativeQuery("SELECT tnd_id FROM tiendas WHERE tnd_activo = true").getResultList();
        return ids.stream().map(Number::longValue).toList();
    }

    /**
     * Si el cliente nunca confirma que recibió su pedido, se asume que sí llegó bien pasadas
     * 72h desde que se marcó "entregado" — igual que hacen Amazon/Mercado Libre, para que un
     * pedido no quede "pendiente de confirmar" para siempre solo porque el cliente no volvió
     * a abrir la app. Usa el historial de estados (no ped_actualizado_en, que se mueve con
     * cualquier cambio del pedido, ej. agregar el link de seguimiento) para saber exactamente
     * desde cuándo está entregado.
     */
    @Transactional
    public int confirmarVencidos(Long tndId) {
        tenantSupport.applyTenant(em);
        return em.createNativeQuery("""
                UPDATE pedidos p
                SET ped_confirmado_cliente_en = now()
                WHERE p.ped_estado = 'entregado'
                  AND p.ped_confirmado_cliente_en IS NULL
                  AND EXISTS (
                      SELECT 1 FROM pedido_historial_estados h
                      WHERE h.phe_ped_id = p.ped_id AND h.phe_estado = 'entregado'
                      GROUP BY h.phe_ped_id
                      HAVING MAX(h.phe_creado_en) <= now() - INTERVAL '72 hours'
                  )
                """)
                .executeUpdate();
    }
}
