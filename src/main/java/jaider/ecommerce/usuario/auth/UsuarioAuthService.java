package jaider.ecommerce.usuario.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.infra.ResendEmailService;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.usuario.UsuarioRepository;
import jaider.ecommerce.usuario.auth.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsuarioAuthService {

    private final UsuarioRepository usuarioRepo;
    private final TenantSupport tenantSupport;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final ResendEmailService emailService;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Value("${google.client-id}")
    private String googleClientId;

    private static final SecureRandom RANDOM = new SecureRandom();

    // ── Registro paso 1: guardar pendiente y enviar código ──────────────────

    @Transactional
    public void preRegister(TiendaRegisterRequest req, Long tndId) {
        tenantSupport.applyTenant(em);
        String email = req.email().trim().toLowerCase();

        long existeUsuario = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM usuarios WHERE usr_email = :email AND usr_tnd_id = :tndId")
                .setParameter("email", email)
                .setParameter("tndId", tndId)
                .getSingleResult()).longValue();

        if (existeUsuario > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email ya registrado");
        }

        // Eliminar pendiente anterior si existe (permite reintentar)
        em.createNativeQuery(
                "DELETE FROM registros_pendientes WHERE rp_email = :email AND rp_tnd_id = :tndId")
                .setParameter("email", email)
                .setParameter("tndId", tndId)
                .executeUpdate();

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hash = passwordEncoder.encode(req.password());
        String nombre = req.nombre() != null ? req.nombre().trim() : "";
        String apellido = req.apellido() != null ? req.apellido().trim() : "";
        String tipoDocumento = req.tipoDocumento() != null ? req.tipoDocumento().trim().toUpperCase() : "";
        String numeroDocumento = req.numeroDocumento() != null ? req.numeroDocumento().trim() : "";
        String datosJson = ("{\"nombre\":\"%s\",\"apellido\":\"%s\",\"password_hash\":\"%s\"," +
                "\"tipo_documento\":\"%s\",\"numero_documento\":\"%s\"}")
                .formatted(escapeJson(nombre), escapeJson(apellido), escapeJson(hash),
                        escapeJson(tipoDocumento), escapeJson(numeroDocumento));

        em.createNativeQuery(
                "INSERT INTO registros_pendientes (rp_email, rp_codigo, rp_datos, rp_expira_en, rp_tnd_id) " +
                "VALUES (:email, :codigo, CAST(:datos AS jsonb), :expira, :tndId)")
                .setParameter("email", email)
                .setParameter("codigo", code)
                .setParameter("datos", datosJson)
                .setParameter("expira", OffsetDateTime.now().plusMinutes(5))
                .setParameter("tndId", tndId)
                .executeUpdate();

        emailService.sendVerification(email, nombre, code);
        log.info("[PRE-REGISTER] email={} tndId={} — código enviado", email, tndId);
    }

    // ── Registro paso 2: verificar código y crear usuario ───────────────────

    @Transactional
    public TiendaAuthResponse verifyAndRegister(TiendaVerifyRequest req, Long tndId) {
        tenantSupport.applyTenant(em);
        String email = req.email().trim().toLowerCase();

        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery(
                    "SELECT rp_id, rp_codigo, rp_datos, rp_expira_en " +
                    "FROM registros_pendientes WHERE rp_email = :email AND rp_tnd_id = :tndId")
                    .setParameter("email", email)
                    .setParameter("tndId", tndId)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PENDING_NOT_FOUND");
        }

        String storedCode = (String) row[1];
        String datosJson  = row[2].toString();
        OffsetDateTime expiry = row[3] instanceof OffsetDateTime odt ? odt
                : OffsetDateTime.parse(row[3].toString());

        if (!storedCode.equals(req.code().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CODE_INVALID");
        }
        if (expiry.isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CODE_EXPIRED");
        }

        // Extraer datos del JSONB
        Map<String, String> datos = parseDatos(datosJson);
        String nombre          = datos.getOrDefault("nombre", "");
        String apellido        = datos.getOrDefault("apellido", "");
        String passwordHash    = datos.getOrDefault("password_hash", "");
        String tipoDocumento   = datos.getOrDefault("tipo_documento", "");
        String numeroDocumento = datos.getOrDefault("numero_documento", "");

        Long usrId = numeroDocumento.isBlank()
                ? crearUsuarioNuevo(email, passwordHash, tndId, nombre, apellido, null, null)
                : ascenderOCrear(email, passwordHash, tndId, nombre, apellido,
                        tipoDocumento.isBlank() ? "CC" : tipoDocumento, numeroDocumento);

        // Eliminar pendiente
        em.createNativeQuery(
                "DELETE FROM registros_pendientes WHERE rp_email = :email AND rp_tnd_id = :tndId")
                .setParameter("email", email)
                .setParameter("tndId", tndId)
                .executeUpdate();

        log.info("[VERIFY-REGISTER] email={} tndId={} usrId={}", email, tndId, usrId);
        String token = jwtService.generate(email, "CLIENTE", tndId, usrId);
        return new TiendaAuthResponse(token, usrId, email, nombre, apellido, null, "EMAIL");
    }

    /**
     * Si ya existe un cliente de venta local (usr_provider=LOCAL, sin credenciales) con esta
     * misma cédula en esta tienda, se asciende ESA MISMA fila a cuenta real (mismo usr_id, por
     * eso su historial de compras previas queda visible sin mover nada). Si no existe, se crea
     * un usuario nuevo normal, guardando también su documento.
     */
    private Long ascenderOCrear(String email, String passwordHash, Long tndId, String nombre, String apellido,
                                 String tipoDocumento, String numeroDocumento) {
        @SuppressWarnings("unchecked")
        List<Number> existentes = em.createNativeQuery("""
                SELECT u.usr_id FROM usuarios u
                JOIN clientes_perfil cp ON cp.cp_usr_id = u.usr_id
                WHERE u.usr_tnd_id = :tndId AND u.usr_provider = CAST('LOCAL' AS auth_provider)
                  AND cp.cp_tipo_documento = CAST(:tipoDocumento AS tipo_documento)
                  AND cp.cp_numero_documento = :numeroDocumento
                """)
                .setParameter("tndId", tndId)
                .setParameter("tipoDocumento", tipoDocumento)
                .setParameter("numeroDocumento", numeroDocumento)
                .getResultList();

        if (existentes.isEmpty()) {
            return crearUsuarioNuevo(email, passwordHash, tndId, nombre, apellido, tipoDocumento, numeroDocumento);
        }

        Long usrId = existentes.get(0).longValue();
        em.createNativeQuery("""
                UPDATE usuarios SET usr_email = :email, usr_provider = CAST('EMAIL' AS auth_provider),
                       usr_password_hash = :hash, usr_acepto_terminos = true
                WHERE usr_id = :usrId
                """)
                .setParameter("email", email)
                .setParameter("hash", passwordHash)
                .setParameter("usrId", usrId)
                .executeUpdate();

        em.createNativeQuery("""
                UPDATE clientes_perfil SET cp_nombre = :nombre, cp_apellido = :apellido
                WHERE cp_usr_id = :usrId
                """)
                .setParameter("nombre", nombre.isBlank() ? null : nombre)
                .setParameter("apellido", apellido.isBlank() ? null : apellido)
                .setParameter("usrId", usrId)
                .executeUpdate();

        log.info("[VERIFY-REGISTER] cuenta de venta local ascendida — usrId={} tndId={}", usrId, tndId);
        return usrId;
    }

    private Long crearUsuarioNuevo(String email, String passwordHash, Long tndId, String nombre, String apellido,
                                    String tipoDocumento, String numeroDocumento) {
        Long usrId = ((Number) em.createNativeQuery(
                "INSERT INTO usuarios (usr_email, usr_provider, usr_password_hash, usr_tnd_id) " +
                "VALUES (:email, CAST('EMAIL' AS auth_provider), :hash, :tndId) RETURNING usr_id")
                .setParameter("email", email)
                .setParameter("hash", passwordHash)
                .setParameter("tndId", tndId)
                .getSingleResult()).longValue();

        em.createNativeQuery("""
                INSERT INTO clientes_perfil (cp_usr_id, cp_tnd_id, cp_nombre, cp_apellido, cp_tipo_documento, cp_numero_documento)
                VALUES (:usrId, :tndId, :nombre, :apellido, CAST(:tipoDocumento AS tipo_documento), :numeroDocumento)
                """)
                .setParameter("usrId", usrId)
                .setParameter("tndId", tndId)
                .setParameter("nombre", nombre.isBlank() ? null : nombre)
                .setParameter("apellido", apellido.isBlank() ? null : apellido)
                .setParameter("tipoDocumento", tipoDocumento)
                .setParameter("numeroDocumento", numeroDocumento)
                .executeUpdate();

        return usrId;
    }

    // ── Reenviar código ──────────────────────────────────────────────────────

    @Transactional
    public void resendCode(String email, Long tndId) {
        tenantSupport.applyTenant(em);
        String normalizedEmail = email.trim().toLowerCase();

        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery(
                    "SELECT rp_id, rp_datos, rp_expira_en FROM registros_pendientes " +
                    "WHERE rp_email = :email AND rp_tnd_id = :tndId")
                    .setParameter("email", normalizedEmail)
                    .setParameter("tndId", tndId)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PENDING_NOT_FOUND");
        }

        OffsetDateTime expiry = row[2] instanceof OffsetDateTime odt ? odt
                : OffsetDateTime.parse(row[2].toString());

        if (expiry.isAfter(OffsetDateTime.now())) {
            long segsRestantes = java.time.Duration.between(OffsetDateTime.now(), expiry).getSeconds();
            throw new ResponseStatusException(HttpStatus.TOO_EARLY, "CODE_STILL_VALID:" + segsRestantes);
        }

        String nombre = parseDatos(row[1].toString()).getOrDefault("nombre", "");
        String newCode = String.format("%06d", RANDOM.nextInt(1_000_000));

        em.createNativeQuery(
                "UPDATE registros_pendientes SET rp_codigo = :code, rp_expira_en = :expira " +
                "WHERE rp_email = :email AND rp_tnd_id = :tndId")
                .setParameter("code", newCode)
                .setParameter("expira", OffsetDateTime.now().plusMinutes(5))
                .setParameter("email", normalizedEmail)
                .setParameter("tndId", tndId)
                .executeUpdate();

        emailService.sendVerification(normalizedEmail, nombre, newCode);
        log.info("[RESEND-CODE] email={} tndId={}", normalizedEmail, tndId);
    }

    // ── Login email + contraseña ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TiendaAuthResponse login(TiendaLoginRequest req, Long tndId) {
        tenantSupport.applyTenant(em);
        String email = req.email().trim().toLowerCase();

        var usuario = usuarioRepo.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Correo o contraseña incorrectos"));

        if ("GOOGLE".equals(usuario.getProvider())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "USE_GOOGLE");
        }
        if (!usuario.isActivo()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta desactivada");
        }
        if (!passwordEncoder.matches(req.password(), usuario.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Correo o contraseña incorrectos");
        }

        String[] perfil = getPerfil(usuario.getId());
        log.info("[LOGIN] email={} tndId={}", email, tndId);
        String token = jwtService.generate(email, "CLIENTE", tndId, usuario.getId());
        return new TiendaAuthResponse(token, usuario.getId(), email,
                perfil[0], perfil[1], perfil[2], "EMAIL");
    }

    // ── Login con Google (ID token) ──────────────────────────────────────────

    @Transactional
    public TiendaAuthResponse googleLogin(TiendaGoogleRequest req, Long tndId) {
        Map<?, ?> info;
        try {
            info = RestClient.create()
                    .get()
                    .uri("https://oauth2.googleapis.com/tokeninfo?id_token=" + req.idToken())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.warn("[GOOGLE_LOGIN] token inválido: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de Google inválido");
        }

        if (info == null || !googleClientId.equals(info.get("aud"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de Google inválido");
        }
        if (!"true".equals(info.get("email_verified"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email no verificado por Google");
        }

        String email    = ((String) info.get("email")).trim().toLowerCase();
        String nombre   = (String) info.get("given_name");
        String apellido = (String) info.get("family_name");
        String googleId = (String) info.get("sub");
        String picture  = (String) info.get("picture");

        tenantSupport.applyTenant(em);

        // Buscar usuario existente
        var usuarioOpt = usuarioRepo.findByEmail(email);

        Long usrId;
        if (usuarioOpt.isEmpty()) {
            // Crear nuevo usuario Google
            usrId = ((Number) em.createNativeQuery(
                    "INSERT INTO usuarios (usr_email, usr_provider, usr_google_id, usr_tnd_id) " +
                    "VALUES (:email, CAST('GOOGLE' AS auth_provider), :googleId, :tndId) RETURNING usr_id")
                    .setParameter("email", email)
                    .setParameter("googleId", googleId)
                    .setParameter("tndId", tndId)
                    .getSingleResult()).longValue();

            em.createNativeQuery(
                    "INSERT INTO clientes_perfil (cp_usr_id, cp_tnd_id, cp_nombre, cp_apellido, cp_avatar) " +
                    "VALUES (:usrId, :tndId, :nombre, :apellido, :avatar)")
                    .setParameter("usrId", usrId)
                    .setParameter("tndId", tndId)
                    .setParameter("nombre", nombre)
                    .setParameter("apellido", apellido)
                    .setParameter("avatar", picture)
                    .executeUpdate();

            log.info("[GOOGLE_LOGIN] nuevo usuario email={} tndId={}", email, tndId);
        } else {
            var usuario = usuarioOpt.get();
            if ("EMAIL".equals(usuario.getProvider())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "USE_PASSWORD");
            }
            usrId = usuario.getId();

            // Actualizar googleId si cambió
            em.createNativeQuery(
                    "UPDATE usuarios SET usr_google_id = :googleId WHERE usr_id = :id")
                    .setParameter("googleId", googleId)
                    .setParameter("id", usrId)
                    .executeUpdate();

            // Actualizar avatar en perfil si aún es de Google
            em.createNativeQuery(
                    "UPDATE clientes_perfil SET cp_avatar = :avatar " +
                    "WHERE cp_usr_id = :id AND (cp_avatar IS NULL OR cp_avatar LIKE '%googleusercontent%')")
                    .setParameter("avatar", picture)
                    .setParameter("id", usrId)
                    .executeUpdate();

            log.info("[GOOGLE_LOGIN] login existente email={} tndId={}", email, tndId);
        }

        String[] perfil = getPerfil(usrId);
        String token = jwtService.generate(email, "CLIENTE", tndId, usrId);
        return new TiendaAuthResponse(token, usrId, email, perfil[0], perfil[1], perfil[2], "GOOGLE");
    }

    // ── Olvidé contraseña ────────────────────────────────────────────────────

    @Transactional
    public void forgotPassword(TiendaForgotPasswordRequest req, Long tndId) {
        tenantSupport.applyTenant(em);
        String email = req.email().trim().toLowerCase();

        var usuarioOpt = usuarioRepo.findByEmail(email);
        if (usuarioOpt.isEmpty()) return; // no revelar si existe o no

        var usuario = usuarioOpt.get();
        if ("GOOGLE".equals(usuario.getProvider())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        em.createNativeQuery(
                "UPDATE usuarios SET usr_reset_token = :code, usr_reset_expiry = :expiry WHERE usr_id = :id")
                .setParameter("code", code)
                .setParameter("expiry", OffsetDateTime.now().plusMinutes(5))
                .setParameter("id", usuario.getId())
                .executeUpdate();

        String[] perfil = getPerfil(usuario.getId());
        emailService.sendPasswordReset(email, perfil[0], code);
        log.info("[FORGOT-PASSWORD] email={} tndId={}", email, tndId);
    }

    // ── Restablecer contraseña ───────────────────────────────────────────────

    @Transactional
    public void resetPassword(TiendaResetPasswordRequest req, Long tndId) {
        tenantSupport.applyTenant(em);

        var usuario = usuarioRepo.findByResetToken(req.code().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "CODE_INVALID"));

        if (usuario.getResetExpiry() == null || usuario.getResetExpiry().isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CODE_EXPIRED");
        }

        String newHash = passwordEncoder.encode(req.newPassword());
        em.createNativeQuery(
                "UPDATE usuarios SET usr_password_hash = :hash, usr_reset_token = NULL, usr_reset_expiry = NULL " +
                "WHERE usr_id = :id")
                .setParameter("hash", newHash)
                .setParameter("id", usuario.getId())
                .executeUpdate();

        log.info("[RESET-PASSWORD] usrId={} tndId={}", usuario.getId(), tndId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String[] getPerfil(Long usrId) {
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                    "SELECT cp_nombre, cp_apellido, cp_avatar FROM clientes_perfil WHERE cp_usr_id = :id")
                    .setParameter("id", usrId)
                    .getSingleResult();
            return new String[]{
                    row[0] != null ? (String) row[0] : "",
                    row[1] != null ? (String) row[1] : "",
                    row[2] != null ? (String) row[2] : null
            };
        } catch (NoResultException e) {
            return new String[]{"", "", null};
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseDatos(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
