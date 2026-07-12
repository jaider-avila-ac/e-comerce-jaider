package jaider.ecommerce.usuario.cliente;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
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
public class TiendaClientePerfilService {

    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public Map<String, Object> getPerfil(Long usrId, Long tndId) {
        tenantSupport.applyTenant(em);
        ensureTenant(tndId);
        return fetchPerfil(usrId, tndId, true);
    }

    /**
     * Consulta base del perfil (nombre, apellido, telefono, documento, direcciones), compartida
     * entre el endpoint del propio cliente (/clientes/me) y el detalle de cliente del admin
     * (ClienteService), para que ambos muestren siempre la misma informacion.
     * El admin pasa soloActivos=false porque tambien debe poder ver clientes desactivados.
     */
    Map<String, Object> fetchPerfil(Long usrId, Long tndId, boolean soloActivos) {
        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery("""
                SELECT u.usr_id,
                       u.usr_email,
                       cp.cp_nombre,
                       cp.cp_apellido,
                       cp.cp_telefono,
                       COALESCE(cp.cp_tipo_documento, 'CC') AS tipo_documento,
                       cp.cp_numero_documento
                FROM usuarios u
                LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = u.usr_id
                WHERE u.usr_id = :usrId
                  AND u.usr_tnd_id = :tndId
                  AND (:soloActivos = false OR u.usr_activo = true)
                """)
                .setParameter("usrId", usrId)
                .setParameter("tndId", tndId)
                .setParameter("soloActivos", soloActivos)
                .getSingleResult();
        } catch (NoResultException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
        }

        Map<String, Object> perfil = new LinkedHashMap<>();
        perfil.put("id", ((Number) row[0]).longValue());
        perfil.put("email", value(row[1]));
        perfil.put("nombre", value(row[2]));
        perfil.put("apellido", value(row[3]));
        perfil.put("telefono", value(row[4]));
        perfil.put("tipo_documento", value(row[5]).isBlank() ? "CC" : value(row[5]));
        perfil.put("numero_documento", value(row[6]));
        perfil.put("direcciones", getDirecciones(usrId, tndId));
        return perfil;
    }

    @Transactional
    public Map<String, Object> updatePerfil(Long usrId, Long tndId, ClientePerfilRequest req) {
        tenantSupport.applyTenant(em);
        ensureTenant(tndId);
        requireUsuario(usrId, tndId);

        // Si no viene número de documento, se guarda NULL (no "") — con el nuevo UNIQUE por
        // tienda, dos clientes con documento en blanco chocarían entre sí si se guardara "".
        String numeroDocumento = clean(req.numeroDocumento());
        String numeroDocumentoFinal = numeroDocumento.isBlank() ? null : numeroDocumento;
        String tipoDocumentoFinal = numeroDocumentoFinal == null ? null
                : (clean(req.tipoDocumento()).isBlank() ? "CC" : clean(req.tipoDocumento()));

        em.createNativeQuery("""
            INSERT INTO clientes_perfil (
                cp_usr_id, cp_tnd_id, cp_nombre, cp_apellido, cp_telefono, cp_tipo_documento, cp_numero_documento
            )
            VALUES (:usrId, :tndId, :nombre, :apellido, :telefono, CAST(:tipoDocumento AS tipo_documento), :numeroDocumento)
            ON CONFLICT (cp_usr_id) DO UPDATE SET
                cp_nombre = EXCLUDED.cp_nombre,
                cp_apellido = EXCLUDED.cp_apellido,
                cp_telefono = EXCLUDED.cp_telefono,
                cp_tipo_documento = EXCLUDED.cp_tipo_documento,
                cp_numero_documento = EXCLUDED.cp_numero_documento
            """)
            .setParameter("usrId", usrId)
            .setParameter("tndId", tndId)
            .setParameter("nombre", clean(req.nombre()))
            .setParameter("apellido", clean(req.apellido()))
            .setParameter("telefono", clean(req.telefono()))
            .setParameter("tipoDocumento", tipoDocumentoFinal)
            .setParameter("numeroDocumento", numeroDocumentoFinal)
            .executeUpdate();

        return getPerfil(usrId, tndId);
    }

    @Transactional
    public List<Map<String, Object>> addDireccion(Long usrId, Long tndId, ClienteDireccionRequest req) {
        tenantSupport.applyTenant(em);
        ensureTenant(tndId);
        requireUsuario(usrId, tndId);

        em.createNativeQuery("""
            INSERT INTO clientes_direcciones (
                cd_usr_id, cd_tnd_id, cd_direccion, cd_complemento, cd_departamento, cd_municipio,
                cd_barrio, cd_apartamento, cd_contacto_nombre, cd_contacto_telefono
            )
            VALUES (
                :usrId, :tndId, :direccion, :complemento, :departamento, :municipio,
                :barrio, :apartamento, :contactoNombre, :contactoTelefono
            )
            """)
            .setParameter("usrId", usrId)
            .setParameter("tndId", tndId)
            .setParameter("direccion", clean(req.direccion()))
            .setParameter("complemento", clean(req.complemento()))
            .setParameter("departamento", clean(req.departamento()))
            .setParameter("municipio", clean(req.municipio()))
            .setParameter("barrio", clean(req.barrio()))
            .setParameter("apartamento", clean(req.apartamento()))
            .setParameter("contactoNombre", clean(req.contactoNombre()))
            .setParameter("contactoTelefono", clean(req.contactoTelefono()))
            .executeUpdate();

        return getDirecciones(usrId, tndId);
    }

    @Transactional
    public List<Map<String, Object>> updateDireccion(Long usrId, Long tndId, Long direccionId, ClienteDireccionRequest req) {
        tenantSupport.applyTenant(em);
        ensureTenant(tndId);

        int updated = em.createNativeQuery("""
            UPDATE clientes_direcciones
            SET cd_direccion = :direccion,
                cd_complemento = :complemento,
                cd_departamento = :departamento,
                cd_municipio = :municipio,
                cd_barrio = :barrio,
                cd_apartamento = :apartamento,
                cd_contacto_nombre = :contactoNombre,
                cd_contacto_telefono = :contactoTelefono
            WHERE cd_id = :direccionId
              AND cd_usr_id = :usrId
              AND cd_tnd_id = :tndId
            """)
            .setParameter("direccionId", direccionId)
            .setParameter("usrId", usrId)
            .setParameter("tndId", tndId)
            .setParameter("direccion", clean(req.direccion()))
            .setParameter("complemento", clean(req.complemento()))
            .setParameter("departamento", clean(req.departamento()))
            .setParameter("municipio", clean(req.municipio()))
            .setParameter("barrio", clean(req.barrio()))
            .setParameter("apartamento", clean(req.apartamento()))
            .setParameter("contactoNombre", clean(req.contactoNombre()))
            .setParameter("contactoTelefono", clean(req.contactoTelefono()))
            .executeUpdate();

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dirección no encontrada");
        }
        return getDirecciones(usrId, tndId);
    }

    @Transactional
    public List<Map<String, Object>> deleteDireccion(Long usrId, Long tndId, Long direccionId) {
        tenantSupport.applyTenant(em);
        ensureTenant(tndId);

        int deleted = em.createNativeQuery("""
            DELETE FROM clientes_direcciones
            WHERE cd_id = :direccionId
              AND cd_usr_id = :usrId
              AND cd_tnd_id = :tndId
            """)
            .setParameter("direccionId", direccionId)
            .setParameter("usrId", usrId)
            .setParameter("tndId", tndId)
            .executeUpdate();

        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dirección no encontrada");
        }
        return getDirecciones(usrId, tndId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getDirecciones(Long usrId, Long tndId) {
        List<Object[]> rows = em.createNativeQuery("""
            SELECT cd_id, cd_direccion, cd_complemento, cd_departamento, cd_municipio,
                   cd_barrio, cd_apartamento, cd_contacto_nombre, cd_contacto_telefono
            FROM clientes_direcciones
            WHERE cd_usr_id = :usrId
              AND cd_tnd_id = :tndId
            ORDER BY cd_creado_en DESC, cd_id DESC
            """)
            .setParameter("usrId", usrId)
            .setParameter("tndId", tndId)
            .getResultList();

        return rows.stream().map(row -> {
            Map<String, Object> direccion = new LinkedHashMap<>();
            direccion.put("id", ((Number) row[0]).longValue());
            direccion.put("direccion", value(row[1]));
            direccion.put("complemento", value(row[2]));
            direccion.put("departamento", value(row[3]));
            direccion.put("municipio", value(row[4]));
            direccion.put("barrio", value(row[5]));
            direccion.put("apartamento", value(row[6]));
            direccion.put("contacto_nombre", value(row[7]));
            direccion.put("contacto_telefono", value(row[8]));
            return direccion;
        }).toList();
    }

    private void requireUsuario(Long usrId, Long tndId) {
        Number count = (Number) em.createNativeQuery("""
            SELECT COUNT(*)
            FROM usuarios
            WHERE usr_id = :usrId
              AND usr_tnd_id = :tndId
              AND usr_activo = true
            """)
            .setParameter("usrId", usrId)
            .setParameter("tndId", tndId)
            .getSingleResult();
        if (count.longValue() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
        }
    }

    private void ensureTenant(Long tndId) {
        if (tndId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant requerido");
        }
        TenantContext.set(tndId.toString());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
