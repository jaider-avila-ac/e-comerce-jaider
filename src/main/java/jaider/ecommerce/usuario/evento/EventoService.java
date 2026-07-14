package jaider.ecommerce.usuario.evento;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.catalogo.publico.PublicCatalogService;
import jaider.ecommerce.catalogo.publico.PublicProductoResponse;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoService {

    private final TenantSupport tenantSupport;
    private final PublicCatalogService publicCatalogService;

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

    /**
     * Últimos productos vistos (sin repetir), en el mismo shape que el resto del catálogo
     * público — antes tenía su propio formato incompleto (sin marca, imágenes reales,
     * descuento, etc.), lo que rompía la visualización en "Vistos recientemente".
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<PublicProductoResponse> recientes(Long usrId, Long tndId, int limit) {
        tenantSupport.applyTenant(em);

        // DISTINCT ON se queda con la vista más reciente de cada producto, y el ORDER BY
        // externo ordena esas últimas vistas entre sí (más reciente primero) antes de recortar.
        List<Number> idsOrdenados = em.createNativeQuery("""
                SELECT eu_entidad_id FROM (
                    SELECT DISTINCT ON (eu_entidad_id) eu_entidad_id, eu_creado_en
                    FROM eventos_usuario
                    WHERE eu_usr_id = :usrId AND eu_tipo = 'vista_producto'
                    ORDER BY eu_entidad_id, eu_creado_en DESC
                ) ultimos
                ORDER BY eu_creado_en DESC
                LIMIT :limit
                """)
                .setParameter("usrId", usrId)
                .setParameter("limit", limit)
                .getResultList();

        List<Long> ids = idsOrdenados.stream().map(Number::longValue).toList();
        return publicCatalogService.getProductosByIds(ids);
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
