package jaider.ecommerce.usuario.cliente;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClienteService {

    private final TenantSupport tenantSupport;
    private final TiendaClientePerfilService perfilService;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAll() {
        tenantSupport.applyTenant(em);
        Long tndId = tenantId();

        List<Object[]> rows = em.createNativeQuery("""
            SELECT u.usr_id,
                   u.usr_email,
                   u.usr_provider::text,
                   u.usr_activo,
                   to_char(u.usr_creado_en AT TIME ZONE 'America/Bogota', 'DD/MM/YYYY HH24:MI') AS creado_en,
                   cp.cp_nombre,
                   cp.cp_apellido,
                   COALESCE(stats.total_pedidos, 0) AS total_pedidos,
                   COALESCE(stats.total_gastado_centavos, 0) AS total_gastado_centavos,
                   cp.cp_telefono AS telefono,
                   COALESCE(cp.cp_tipo_documento::text, 'CC') AS tipo_documento,
                   cp.cp_numero_documento,
                   latest_dir.cd_municipio AS ciudad
            FROM usuarios u
            LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = u.usr_id
            LEFT JOIN (
                SELECT ped_usr_id,
                       COUNT(*) AS total_pedidos,
                       SUM(CASE
                           WHEN ped_estado::text NOT IN ('cancelado', 'devuelto')
                           THEN ped_total_centavos
                           ELSE 0
                       END) AS total_gastado_centavos
                FROM pedidos
                WHERE ped_tnd_id = :tndId
                  AND EXISTS (
                      SELECT 1 FROM pagos pg
                      WHERE pg.pag_ped_id = pedidos.ped_id
                        AND pg.pag_estado = CAST('APPROVED' AS estado_pago)
                  )
                GROUP BY ped_usr_id
            ) stats ON stats.ped_usr_id = u.usr_id
            LEFT JOIN LATERAL (
                SELECT cd.cd_municipio
                FROM clientes_direcciones cd
                WHERE cd.cd_usr_id = u.usr_id
                  AND cd.cd_tnd_id = :tndId
                ORDER BY cd.cd_creado_en DESC
                LIMIT 1
            ) latest_dir ON true
            WHERE u.usr_tnd_id = :tndId
            ORDER BY u.usr_creado_en DESC, u.usr_id DESC
            """)
            .setParameter("tndId", tndId)
            .getResultList();

        return rows.stream().map(this::toCliente).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getById(Long id) {
        tenantSupport.applyTenant(em);
        Long tndId = tenantId();

        // Perfil (nombre, apellido, telefono, documento, direcciones) es el mismo que ve
        // el propio cliente en /clientes/me — una sola fuente de verdad para ambos.
        // soloActivos=false: el admin tambien debe poder ver clientes desactivados.
        Map<String, Object> cliente = new LinkedHashMap<>(perfilService.fetchPerfil(id, tndId, false));
        cliente.putAll(fetchStatsAdmin(id, tndId));
        return cliente;
    }

    private Map<String, Object> fetchStatsAdmin(Long id, Long tndId) {
        Object[] row = (Object[]) em.createNativeQuery("""
            SELECT u.usr_provider::text,
                   u.usr_activo,
                   to_char(u.usr_creado_en AT TIME ZONE 'America/Bogota', 'DD/MM/YYYY HH24:MI') AS creado_en,
                   COALESCE(stats.total_pedidos, 0) AS total_pedidos,
                   COALESCE(stats.total_gastado_centavos, 0) AS total_gastado_centavos
            FROM usuarios u
            LEFT JOIN (
                SELECT ped_usr_id,
                       COUNT(*) AS total_pedidos,
                       SUM(CASE
                           WHEN ped_estado::text NOT IN ('cancelado', 'devuelto')
                           THEN ped_total_centavos
                           ELSE 0
                       END) AS total_gastado_centavos
                FROM pedidos
                WHERE ped_tnd_id = :tndId
                  AND EXISTS (
                      SELECT 1 FROM pagos pg
                      WHERE pg.pag_ped_id = pedidos.ped_id
                        AND pg.pag_estado = CAST('APPROVED' AS estado_pago)
                  )
                GROUP BY ped_usr_id
            ) stats ON stats.ped_usr_id = u.usr_id
            WHERE u.usr_id = :id
              AND u.usr_tnd_id = :tndId
            """)
            .setParameter("id", id)
            .setParameter("tndId", tndId)
            .getSingleResult();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("provider", row[0] != null ? row[0] : "EMAIL");
        stats.put("activo", row[1]);
        stats.put("creado_en", row[2]);
        stats.put("total_pedidos", ((Number) row[3]).longValue());
        stats.put("total_gastado", ((Number) row[4]).longValue() / 100L);
        return stats;
    }

    @Transactional
    public void deactivate(Long id) {
        tenantSupport.applyTenant(em);
        Long tndId = tenantId();

        int updated = em.createNativeQuery("""
            UPDATE usuarios
            SET usr_activo = false
            WHERE usr_id = :id
              AND usr_tnd_id = :tndId
            """)
            .setParameter("id", id)
            .setParameter("tndId", tndId)
            .executeUpdate();

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
        }
    }

    private Map<String, Object> toCliente(Object[] row) {
        Map<String, Object> cliente = new LinkedHashMap<>();
        cliente.put("id", ((Number) row[0]).longValue());
        cliente.put("email", row[1] != null ? row[1] : "");
        cliente.put("provider", row[2] != null ? row[2] : "EMAIL");
        cliente.put("activo", row[3]);
        cliente.put("creado_en", row[4]);
        cliente.put("nombre", row[5] != null ? row[5] : "");
        cliente.put("apellido", row[6] != null ? row[6] : "");
        cliente.put("total_pedidos", ((Number) row[7]).longValue());
        cliente.put("total_gastado", ((Number) row[8]).longValue() / 100L);
        cliente.put("telefono", row[9]);
        cliente.put("tipo_documento", row[10]);
        cliente.put("numero_documento", row[11]);
        cliente.put("ciudad", row[12]);
        return cliente;
    }

    private Long tenantId() {
        String tndId = TenantContext.get();
        if (tndId == null || tndId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant requerido");
        }
        return Long.parseLong(tndId);
    }
}
