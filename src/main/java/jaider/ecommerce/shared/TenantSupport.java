package jaider.ecommerce.shared;

import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Inyecta el tenant actual en la sesión PostgreSQL mediante set_config().
 * Debe llamarse al inicio de cada método @Transactional que acceda a tablas con RLS.
 *
 * set_config('app.current_tnd_id', id, true) equivale a SET LOCAL dentro de la tx activa.
 */
@Component
public class TenantSupport {

    /**
     * Para toda operación tenantizada real (§3.3 del plan: "toda operación que acceda a
     * información de negocio debe requerir tenant... no continuar silenciosamente sin
     * aplicarlo"). Si no hay tenant en contexto, rechaza con 400 en vez de dejar que el método
     * siga y RLS devuelva una lista vacía silenciosa — eso ocultaba errores reales de resolución
     * de tenant (dominio no registrado, header ausente en una ruta que lo necesitaba, etc.)
     * detrás de una respuesta 200 vacía indistinguible de "esta tienda no tiene datos".
     */
    public void requireTenant(EntityManager em) {
        String tndId = TenantContext.get();
        if (tndId == null || tndId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo determinar la tienda de esta solicitud");
        }
        apply(em, tndId);
    }

    /**
     * Versión permisiva — SOLO para los 2 puntos donde "sin tenant" es un caso válido por
     * diseño: login/me de admin_users (sirve tanto a un admin de una tienda como al superadmin,
     * que nunca tiene tenant por definición — ver chk_admin_users_superadmin y la política RLS
     * de admin_users, que deja pasar sin tenant cuando rol='superadmin'). Cualquier otro
     * servicio tenantizado debe usar requireTenant().
     */
    public void applyTenant(EntityManager em) {
        String tndId = TenantContext.get();
        if (tndId == null || tndId.isBlank()) return;
        apply(em, tndId);
    }

    private void apply(EntityManager em, String tndId) {
        em.createNativeQuery("SELECT set_config('app.current_tnd_id', :id, true)")
                .setParameter("id", tndId)
                .getSingleResult();
    }
}
