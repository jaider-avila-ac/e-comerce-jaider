package jaider.ecommerce.shared;

import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Inyecta el tenant actual en la sesión PostgreSQL mediante set_config().
 * Debe llamarse al inicio de cada método @Transactional que acceda a tablas con RLS.
 *
 * set_config('app.current_tnd_id', id, true) equivale a SET LOCAL dentro de la tx activa.
 */
@Component
public class TenantSupport {

    public void applyTenant(EntityManager em) {
        String tndId = TenantContext.get();
        if (tndId == null || tndId.isBlank()) return;
        em.createNativeQuery("SELECT set_config('app.current_tnd_id', :id, true)")
                .setParameter("id", tndId)
                .getSingleResult();
    }
}
