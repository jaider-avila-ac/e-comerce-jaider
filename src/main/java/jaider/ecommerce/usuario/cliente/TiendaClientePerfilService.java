package jaider.ecommerce.usuario.cliente;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.geo.ColombiaGeoService;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.usuario.Usuario;
import jaider.ecommerce.usuario.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TiendaClientePerfilService {

    private final TenantSupport tenantSupport;
    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final TiendaRepository tiendaRepository;
    private final ColombiaGeoService geoService;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public Map<String, Object> getPerfil(Long usrId, Long tndId) {
        tenantSupport.requireTenant(em);
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
                       cp.cp_numero_documento,
                       COALESCE(cp.cp_acepta_promo, true) AS acepta_promo
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
        perfil.put("acepta_promo", (Boolean) row[7]);
        perfil.put("direcciones", getDirecciones(usrId, tndId));
        return perfil;
    }

    @Transactional
    public Map<String, Object> updatePerfil(Long usrId, Long tndId, ClientePerfilRequest req) {
        tenantSupport.requireTenant(em);
        ensureTenant(tndId);
        requireUsuario(usrId, tndId);

        // Si no viene número de documento, se guarda NULL (no "") — con el nuevo UNIQUE por
        // tienda, dos clientes con documento en blanco chocarían entre sí si se guardara "".
        String numeroDocumento = clean(req.numeroDocumento());
        String numeroDocumentoFinal = numeroDocumento.isBlank() ? null : numeroDocumento;
        String tipoDocumentoFinal = numeroDocumentoFinal == null ? null
                : (clean(req.tipoDocumento()).isBlank() ? "CC" : clean(req.tipoDocumento()));

        // Si esta cédula ya es de un cliente de mostrador (venta local, sin cuenta propia — ver
        // VentaLocalService), se absorbe ANTES del upsert: si no, el UNIQUE(tnd, tipo, numero) de
        // clientes_perfil rechazaría este guardado (esa cédula "ya está tomada" por la fila de
        // mostrador), dejando a un cliente real de Google/email sin poder guardar su documento.
        if (numeroDocumentoFinal != null) {
            fusionarClienteMostrador(usrId, tndId, tipoDocumentoFinal, numeroDocumentoFinal);
        }

        // aceptaPromo puede venir null (formularios que no tocan esta preferencia, ej. datos
        // personales) — COALESCE conserva el valor ya guardado en ese caso, tanto al insertar
        // (si la fila no existe aún, cae al DEFAULT true de la columna) como al actualizar.
        em.createNativeQuery("""
            INSERT INTO clientes_perfil (
                cp_usr_id, cp_tnd_id, cp_nombre, cp_apellido, cp_telefono, cp_tipo_documento, cp_numero_documento,
                cp_acepta_promo
            )
            VALUES (:usrId, :tndId, :nombre, :apellido, :telefono, CAST(:tipoDocumento AS tipo_documento), :numeroDocumento,
                COALESCE(CAST(:aceptaPromo AS BOOLEAN), true))
            ON CONFLICT (cp_usr_id) DO UPDATE SET
                cp_nombre = EXCLUDED.cp_nombre,
                cp_apellido = EXCLUDED.cp_apellido,
                cp_telefono = EXCLUDED.cp_telefono,
                cp_tipo_documento = EXCLUDED.cp_tipo_documento,
                cp_numero_documento = EXCLUDED.cp_numero_documento,
                cp_acepta_promo = COALESCE(CAST(:aceptaPromo AS BOOLEAN), clientes_perfil.cp_acepta_promo)
            """)
            .setParameter("usrId", usrId)
            .setParameter("tndId", tndId)
            .setParameter("nombre", clean(req.nombre()))
            .setParameter("apellido", clean(req.apellido()))
            .setParameter("telefono", clean(req.telefono()))
            .setParameter("tipoDocumento", tipoDocumentoFinal)
            .setParameter("numeroDocumento", numeroDocumentoFinal)
            .setParameter("aceptaPromo", req.aceptaPromo())
            .executeUpdate();

        return getPerfil(usrId, tndId);
    }

    /** Si esta cédula ya pertenece a un cliente de mostrador (usr_provider=LOCAL, sin cuenta
     *  propia — ver VentaLocalService.resolverCliente), sus pedidos pasan al usuario real que
     *  se está actualizando (mismo historial de compras que si siempre hubiera comprado
     *  logueado) y esa fila de mostrador se elimina. Mismo criterio de "ascender" que ya existe
     *  para registro por email (ver UsuarioAuthService.ascenderOCrear) — acá hace falta aparte
     *  porque Google no pide cédula al iniciar sesión, así que ese cruce solo puede pasar
     *  después, cuando el cliente completa su documento en el perfil. */
    private void fusionarClienteMostrador(Long usrId, Long tndId, String tipoDocumento, String numeroDocumento) {
        @SuppressWarnings("unchecked")
        List<Number> existentes = em.createNativeQuery("""
                SELECT u.usr_id FROM usuarios u
                JOIN clientes_perfil cp ON cp.cp_usr_id = u.usr_id
                WHERE u.usr_tnd_id = :tndId AND u.usr_provider = CAST('LOCAL' AS auth_provider)
                  AND cp.cp_tipo_documento = CAST(:tipoDocumento AS tipo_documento)
                  AND cp.cp_numero_documento = :numeroDocumento
                  AND u.usr_id <> :usrId
                """)
                .setParameter("tndId", tndId)
                .setParameter("tipoDocumento", tipoDocumento)
                .setParameter("numeroDocumento", numeroDocumento)
                .setParameter("usrId", usrId)
                .getResultList();
        if (existentes.isEmpty()) return;

        Long viejoUsrId = existentes.get(0).longValue();
        em.createNativeQuery("UPDATE pedidos SET ped_usr_id = :nuevo WHERE ped_usr_id = :viejo")
                .setParameter("nuevo", usrId).setParameter("viejo", viejoUsrId).executeUpdate();
        em.createNativeQuery("DELETE FROM clientes_perfil WHERE cp_usr_id = :id")
                .setParameter("id", viejoUsrId).executeUpdate();
        em.createNativeQuery("DELETE FROM usuarios WHERE usr_id = :id")
                .setParameter("id", viejoUsrId).executeUpdate();

        log.info("[FusionClienteMostrador] usuario {} absorbió al cliente de mostrador {} (documento {} {})",
                usrId, viejoUsrId, tipoDocumento, numeroDocumento);
    }

    /** Cambio de contraseña autenticado (distinto de forgot/reset-password, que son para
     *  usuario NO logueado vía código de correo). Exige la contraseña actual. */
    @Transactional
    public void cambiarPassword(Long usrId, Long tndId, ClientePasswordRequest req) {
        tenantSupport.requireTenant(em);
        ensureTenant(tndId);

        Usuario usuario = usuarioRepository.findById(usrId)
                .filter(u -> tndId.equals(u.getTndId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado"));

        if ("GOOGLE".equals(usuario.getProvider())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT");
        }
        if (req.passwordActual() == null || req.passwordActual().isBlank()
                || !passwordEncoder.matches(req.passwordActual(), usuario.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contraseña actual incorrecta");
        }
        if (req.passwordNueva() == null || req.passwordNueva().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La nueva contraseña debe tener al menos 8 caracteres");
        }

        String hash = passwordEncoder.encode(req.passwordNueva());
        em.createNativeQuery("UPDATE usuarios SET usr_password_hash = :hash WHERE usr_id = :id")
                .setParameter("hash", hash)
                .setParameter("id", usrId)
                .executeUpdate();
    }

    @Transactional
    public List<Map<String, Object>> addDireccion(Long usrId, Long tndId, ClienteDireccionRequest req) {
        tenantSupport.requireTenant(em);
        ensureTenant(tndId);
        requireUsuario(usrId, tndId);
        validarCamposParaEnvia(tndId, req);

        em.createNativeQuery("""
            INSERT INTO clientes_direcciones (
                cd_usr_id, cd_tnd_id, cd_direccion, cd_complemento, cd_departamento, cd_municipio,
                cd_barrio, cd_apartamento, cd_contacto_nombre, cd_contacto_telefono, cd_codigo_postal
            )
            VALUES (
                :usrId, :tndId, :direccion, :complemento, :departamento, :municipio,
                :barrio, :apartamento, :contactoNombre, :contactoTelefono, :codigoPostal
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
            .setParameter("codigoPostal", clean(req.codigoPostal()))
            .executeUpdate();

        return getDirecciones(usrId, tndId);
    }

    @Transactional
    public List<Map<String, Object>> updateDireccion(Long usrId, Long tndId, Long direccionId, ClienteDireccionRequest req) {
        tenantSupport.requireTenant(em);
        ensureTenant(tndId);
        validarCamposParaEnvia(tndId, req);

        int updated = em.createNativeQuery("""
            UPDATE clientes_direcciones
            SET cd_direccion = :direccion,
                cd_complemento = :complemento,
                cd_departamento = :departamento,
                cd_municipio = :municipio,
                cd_barrio = :barrio,
                cd_apartamento = :apartamento,
                cd_contacto_nombre = :contactoNombre,
                cd_contacto_telefono = :contactoTelefono,
                cd_codigo_postal = :codigoPostal
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
            .setParameter("codigoPostal", clean(req.codigoPostal()))
            .executeUpdate();

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dirección no encontrada");
        }
        return getDirecciones(usrId, tndId);
    }

    @Transactional
    public List<Map<String, Object>> deleteDireccion(Long usrId, Long tndId, Long direccionId) {
        tenantSupport.requireTenant(em);
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
                   cd_barrio, cd_apartamento, cd_contacto_nombre, cd_contacto_telefono, cd_codigo_postal
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
            direccion.put("codigo_postal", value(row[9]));
            return direccion;
        }).toList();
    }

    // PLAN_INTEGRACION_ENVIA.md, Fase 3 — sin esto, una dirección guardada con campos vacíos
    // pasaría el checkout sin error (resolverDireccion() de PedidoCreacionService solo valida
    // que exista, no que esté completa) y el precio de envío real no se podría calcular después.
    // Solo aplica para tiendas en modo 'envia' — contra_entrega/fijo no lo necesitan y siguen
    // aceptando direcciones parciales como siempre (Calzacaribe no se ve afectada).
    private void validarCamposParaEnvia(Long tndId, ClienteDireccionRequest req) {
        Tienda tienda = tiendaRepository.findById(tndId).orElse(null);
        if (tienda == null || !"envia".equals(tienda.getEnvioModo())) {
            return;
        }
        // LinkedHashMap (no Map.of): los valores pueden venir null y Map.of los rechaza.
        Map<String, String> requeridos = new LinkedHashMap<>();
        requeridos.put("dirección", req.direccion());
        requeridos.put("municipio", req.municipio());
        requeridos.put("departamento", req.departamento());
        requeridos.put("código postal", req.codigoPostal());
        requeridos.put("nombre de contacto", req.contactoNombre());
        requeridos.put("teléfono de contacto", req.contactoTelefono());

        List<String> faltantes = requeridos.entrySet().stream()
                .filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(Map.Entry::getKey)
                .toList();
        if (!faltantes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta tienda calcula el envío real — completa: " + String.join(", ", faltantes));
        }

        // El departamento/municipio deben ser un par real de Colombia (ColombiaGeoService, mismo
        // catálogo DANE/DIVIPOLA que ofrece el frontend) — sin esto, Envia no puede resolver la
        // ciudad/estado reales al cotizar. No se valida para contra_entrega/fijo: ahí nunca se
        // usan para nada más que mostrar la dirección, y exigirlo arriesgaría romper direcciones
        // viejas de Calzacaribe guardadas antes de que este catálogo existiera.
        if (!geoService.esDepartamentoValido(req.departamento())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El departamento \"" + req.departamento() + "\" no es válido");
        }
        if (!geoService.esMunicipioValido(req.departamento(), req.municipio())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El municipio \"" + req.municipio() + "\" no pertenece a " + req.departamento());
        }
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
