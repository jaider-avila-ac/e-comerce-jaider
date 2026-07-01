package jaider.ecommerce.usuario.evento;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoService {

    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void registrar(EventoRequest req, Long usrId, Long tndId) {
        tenantSupport.applyTenant(em);
        em.createNativeQuery(
                "INSERT INTO eventos_usuario (eu_usr_id, eu_tipo, eu_entidad_tipo, eu_entidad_id) " +
                "VALUES (:usrId, :tipo, :entidadTipo, :entidadId)")
                .setParameter("usrId", usrId)
                .setParameter("tipo", req.tipo())
                .setParameter("entidadTipo", req.entidadTipo())
                .setParameter("entidadId", req.entidadId())
                .executeUpdate();
        log.debug("[EVENTO] usr={} tipo={} entidad={}/{}", usrId, req.tipo(), req.entidadTipo(), req.entidadId());
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recientes(Long usrId, Long tndId, int limit) {
        tenantSupport.applyTenant(em);

        // Últimos productos vistos (sin repetir), unidos a datos del producto
        List<Object[]> rows = em.createNativeQuery("""
                SELECT DISTINCT ON (e.eu_entidad_id)
                    p.prd_id, p.prd_nombre, p.prd_slug,
                    p.prd_precio_centavos, p.prd_precio_descuento_centavos,
                    pi.pi_url,
                    e.eu_creado_en
                FROM eventos_usuario e
                JOIN productos p ON p.prd_id = e.eu_entidad_id
                LEFT JOIN producto_imagenes pi
                    ON pi.pi_prd_id = p.prd_id
                    AND pi.pi_orden = (SELECT MIN(pi2.pi_orden) FROM producto_imagenes pi2 WHERE pi2.pi_prd_id = p.prd_id)
                WHERE e.eu_usr_id = :usrId
                  AND e.eu_tipo = 'vista_producto'
                  AND p.prd_activo = true
                ORDER BY e.eu_entidad_id, e.eu_creado_en DESC
                LIMIT :limit
                """)
                .setParameter("usrId", usrId)
                .setParameter("limit", limit)
                .getResultList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Long precio = r[4] != null
                    ? ((Number) r[4]).longValue() / 100L
                    : ((Number) r[3]).longValue() / 100L;
            Long precioAntes = r[4] != null ? ((Number) r[3]).longValue() / 100L : null;
            result.add(Map.of(
                    "id",          ((Number) r[0]).longValue(),
                    "nombre",      r[1],
                    "slug",        r[2] != null ? r[2] : "",
                    "precio",      precio,
                    "precio_antes", precioAntes != null ? precioAntes : precio,
                    "imagen_url",  r[5] != null ? r[5] : ""
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> categoriaFavorita(Long usrId, Long tndId) {
        tenantSupport.applyTenant(em);
        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.prd_cat_id, c.cat_nombre, COUNT(*) AS visitas
                FROM eventos_usuario e
                JOIN productos p ON p.prd_id = e.eu_entidad_id
                JOIN categorias c ON c.cat_id = p.prd_cat_id
                WHERE e.eu_usr_id = :usrId
                  AND e.eu_tipo = 'vista_producto'
                GROUP BY p.prd_cat_id, c.cat_nombre
                ORDER BY visitas DESC
                LIMIT 1
                """)
                .setParameter("usrId", usrId)
                .getResultList();

        if (rows.isEmpty()) return Map.of();
        Object[] r = rows.get(0);
        return Map.of(
                "id",     ((Number) r[0]).longValue(),
                "nombre", r[1]
        );
    }
}
