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
                   latest_dir.dir_snapshot ->> 'contactoTelefono' AS telefono,
                   NULL AS tipo_documento,
                   NULL AS numero_documento,
                   COALESCE(
                       latest_dir.dir_snapshot ->> 'municipio',
                       latest_dir.dir_snapshot ->> 'ciudad'
                   ) AS ciudad
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
                GROUP BY ped_usr_id
            ) stats ON stats.ped_usr_id = u.usr_id
            LEFT JOIN LATERAL (
                SELECT p.ped_dir_snapshot AS dir_snapshot
                FROM pedidos p
                WHERE p.ped_usr_id = u.usr_id
                  AND p.ped_tnd_id = :tndId
                ORDER BY p.ped_creado_en DESC
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
    @SuppressWarnings("unchecked")
    public Map<String, Object> getById(Long id) {
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
                   COALESCE(
                       latest_dir.dir_snapshot ->> 'contactoTelefono',
                       latest_dir.dir_snapshot ->> 'telefono'
                   ) AS telefono,
                   NULL AS tipo_documento,
                   NULL AS numero_documento,
                   COALESCE(
                       latest_dir.dir_snapshot ->> 'municipio',
                       latest_dir.dir_snapshot ->> 'ciudad'
                   ) AS ciudad
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
                GROUP BY ped_usr_id
            ) stats ON stats.ped_usr_id = u.usr_id
            LEFT JOIN LATERAL (
                SELECT p.ped_dir_snapshot AS dir_snapshot
                FROM pedidos p
                WHERE p.ped_usr_id = u.usr_id
                  AND p.ped_tnd_id = :tndId
                ORDER BY p.ped_creado_en DESC
                LIMIT 1
            ) latest_dir ON true
            WHERE u.usr_id = :id
              AND u.usr_tnd_id = :tndId
            """)
            .setParameter("id", id)
            .setParameter("tndId", tndId)
            .getResultList();

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
        }

        Map<String, Object> cliente = toCliente(rows.get(0));
        cliente.put("direcciones", getDirecciones(id, tndId));
        return cliente;
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDirecciones(Long usrId, Long tndId) {
        List<Object[]> rows = em.createNativeQuery("""
            SELECT p.ped_id,
                   p.ped_dir_snapshot ->> 'direccion' AS direccion,
                   p.ped_dir_snapshot ->> 'complemento' AS complemento,
                   p.ped_dir_snapshot ->> 'departamento' AS departamento,
                   COALESCE(
                       p.ped_dir_snapshot ->> 'municipio',
                       p.ped_dir_snapshot ->> 'ciudad'
                   ) AS municipio,
                   p.ped_dir_snapshot ->> 'barrio' AS barrio,
                   p.ped_dir_snapshot ->> 'apartamento' AS apartamento,
                   COALESCE(
                       p.ped_dir_snapshot ->> 'contactoNombre',
                       p.ped_dir_snapshot ->> 'nombre_destinatario'
                   ) AS contacto_nombre,
                   COALESCE(
                       p.ped_dir_snapshot ->> 'contactoTelefono',
                       p.ped_dir_snapshot ->> 'telefono'
                   ) AS contacto_telefono
            FROM pedidos p
            WHERE p.ped_usr_id = :usrId
              AND p.ped_tnd_id = :tndId
              AND p.ped_dir_snapshot IS NOT NULL
            ORDER BY p.ped_creado_en DESC, p.ped_id DESC
            """)
            .setParameter("usrId", usrId)
            .setParameter("tndId", tndId)
            .getResultList();

        List<Map<String, Object>> direcciones = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object[] row : rows) {
            String key = java.util.Arrays.toString(java.util.Arrays.copyOfRange(row, 1, row.length));
            if (!seen.add(key)) continue;

            Map<String, Object> direccion = new LinkedHashMap<>();
            direccion.put("id", ((Number) row[0]).longValue());
            direccion.put("direccion", row[1]);
            direccion.put("complemento", row[2]);
            direccion.put("departamento", row[3]);
            direccion.put("municipio", row[4]);
            direccion.put("barrio", row[5]);
            direccion.put("apartamento", row[6]);
            direccion.put("contacto_nombre", row[7]);
            direccion.put("contacto_telefono", row[8]);
            direcciones.add(direccion);
        }
        return direcciones;
    }

    private Long tenantId() {
        String tndId = TenantContext.get();
        if (tndId == null || tndId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant requerido");
        }
        return Long.parseLong(tndId);
    }
}
