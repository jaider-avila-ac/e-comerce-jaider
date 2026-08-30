package jaider.ecommerce.auth.superadmin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vista de solo lectura de la plataforma completa para el rol superadmin — nunca datos
 * operativos ni por tienda (decisión explícita del usuario, 2026-08-30). Se apoya en
 * fn_superadmin_resumen(), una función SQL SECURITY DEFINER (dueña: postgres, superusuario) que
 * es el ÚNICO punto del sistema autorizado a cruzar el RLS de todas las tiendas — y solo para
 * devolver conteos agregados, nunca filas individuales.
 */
@Service
public class SuperadminService {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public SuperadminResumenResponse resumen() {
        Object[] row = (Object[]) em.createNativeQuery("SELECT * FROM fn_superadmin_resumen()")
                .getSingleResult();
        return new SuperadminResumenResponse(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue()
        );
    }
}
