package jaider.ecommerce.tienda.aprovisionamiento;

import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaDominio;
import jaider.ecommerce.tienda.TiendaDominioRepository;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.tienda.integracion.IntegracionSalud;
import jaider.ecommerce.tienda.integracion.TenantIntegrationHealthService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Aprovisionamiento controlado de una tienda nueva (§15 del plan) — "no se recomienda insertar
 * manualmente todos estos datos mediante SQL como procedimiento normal". No hay interfaz
 * gráfica (el plan dice que no es obligatoria): esto es el "comando administrativo interno" que
 * sugiere, expuesto vía {@link TenantProvisioningController} protegido por una llave compartida
 * en vez del login normal (todavía no existe un flujo real de superadmin — ver
 * [[multitenant_plan]] en la memoria del proyecto, es una operación deliberadamente rara y
 * manual, no de autoservicio).
 *
 * PRECONDICIÓN operativa: las variables de entorno WOMPI_&lt;alias&gt;_*, RESEND_&lt;alias&gt;_*
 * y CLOUDINARY_&lt;alias&gt;_* de la tienda nueva deben existir YA en el proceso en ejecución
 * (el operador las agrega y reinicia el backend) antes de llamar a este servicio — de lo
 * contrario los chequeos de salud fallan y la tienda queda creada pero inactiva.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final TiendaRepository tiendaRepo;
    private final TiendaDominioRepository dominioRepo;
    private final AdminUserRepository adminUserRepository;
    private final TenantIntegrationHealthService healthService;
    private final TenantSupport tenantSupport;
    private final PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public TenantProvisioningResult provisionar(TenantProvisioningRequest req) {
        String slug = req.slug().trim().toLowerCase(Locale.ROOT);
        String alias = req.secretAlias().trim().toUpperCase(Locale.ROOT);
        String dominio = normalizarDominio(req.dominioPrincipal());
        String adminEmail = req.adminEmail().trim().toLowerCase(Locale.ROOT);
        String nit = req.nit().trim();

        validarUnicidad(slug, alias, dominio, adminEmail, nit);

        Tienda tienda = new Tienda();
        tienda.setSlug(slug);
        tienda.setSecretAlias(alias);
        tienda.setNombre(req.nombreComercial().trim());
        tienda.setRazonSocial(req.razonSocial().trim());
        tienda.setNit(nit);
        tienda.setEmailContacto(req.emailContacto().trim().toLowerCase(Locale.ROOT));
        tienda.setEmailNotificacionPedidos(blankToNull(req.emailNotificacionPedidos()));
        tienda.setWhatsappPrincipal(blankToNull(req.whatsapp()));
        tienda.setEnvioModo(req.envioModo() != null && !req.envioModo().isBlank() ? req.envioModo().trim() : "contra_entrega");
        if (req.envioCostoCentavos() != null) tienda.setEnvioCostoCentavos(req.envioCostoCentavos());
        if (req.envioGratisActivo() != null) tienda.setEnvioGratisActivo(req.envioGratisActivo());
        if (req.envioGratisDesdeCentavos() != null) tienda.setEnvioGratisDesdeCentavos(req.envioGratisDesdeCentavos());
        tienda.setActivo(false); // inactiva hasta pasar salud + aislamiento
        tienda.setCreadoEn(OffsetDateTime.now());
        tienda = tiendaRepo.saveAndFlush(tienda);
        Long tndId = tienda.getId();

        TiendaDominio tiendaDominio = new TiendaDominio();
        tiendaDominio.setTndId(tndId);
        tiendaDominio.setDominio(dominio);
        tiendaDominio.setPrincipal(true);
        tiendaDominio.setActivo(true);
        tiendaDominio.setCreadoEn(OffsetDateTime.now());
        dominioRepo.save(tiendaDominio);

        // A partir de acá se toca admin_users, que SÍ tiene RLS — hace falta el contexto del
        // tenant recién creado para que el INSERT pase el WITH CHECK de la política.
        TenantContext.set(tndId.toString());
        try {
            tenantSupport.requireTenant(em);

            String hash = passwordEncoder.encode(req.adminPassword());
            Number adminIdNum = (Number) em.createNativeQuery("""
                    INSERT INTO admin_users (email, password, nombre, rol, tienda_id, activo)
                    VALUES (:email, :password, :nombre, CAST('admin' AS rol_empleado), :tiendaId, true)
                    RETURNING id
                    """)
                    .setParameter("email", adminEmail)
                    .setParameter("password", hash)
                    .setParameter("nombre", req.adminNombre().trim())
                    .setParameter("tiendaId", tndId)
                    .getSingleResult();
            Long adminId = adminIdNum.longValue();

            List<IntegracionSalud> salud = healthService.chequear(tndId);
            boolean saludOk = salud.stream().allMatch(IntegracionSalud::ok);
            boolean aislamientoOk = probarAislamiento(tndId);

            boolean activar = saludOk && aislamientoOk;
            tienda.setActivo(activar);
            tiendaRepo.save(tienda);

            String mensaje = activar
                    ? "Tienda creada y activada correctamente."
                    : "Tienda creada pero INACTIVA — revisa las integraciones y/o el aislamiento antes de activarla manualmente.";
            log.info("[Aprovisionamiento] tenant={} slug={} alias={} activada={} aislamientoOk={}",
                    tndId, slug, alias, activar, aislamientoOk);
            return new TenantProvisioningResult(tndId, adminId, activar, salud, aislamientoOk, mensaje);
        } finally {
            TenantContext.clear();
        }
    }

    /** §15 paso 9 — con el contexto de la tienda nueva, ninguna consulta a una tabla tenantizada
     *  debe poder ver filas de otro tenant. Se prueba contra `productos` (vacía para una tienda
     *  recién creada, así que el resultado esperado es 0 filas visibles pase lo que pase). */
    private boolean probarAislamiento(Long tndId) {
        try {
            Number filasDeOtroTenant = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM productos WHERE prd_tnd_id <> :tndId")
                    .setParameter("tndId", tndId)
                    .getSingleResult();
            return filasDeOtroTenant.longValue() == 0;
        } catch (Exception e) {
            log.error("[Aprovisionamiento] Prueba de aislamiento falló para tenant {}: {}", tndId, e.getMessage());
            return false;
        }
    }

    private void validarUnicidad(String slug, String alias, String dominio, String adminEmail, String nit) {
        if (tiendaRepo.findBySlug(slug).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una tienda con ese slug");
        }
        if (!alias.matches("^[A-Z0-9_]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El alias de secretos debe ser solo mayúsculas, números y guion bajo");
        }
        if (tiendaRepo.existsBySecretAlias(alias)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una tienda con ese alias de secretos");
        }
        if (dominioRepo.findByDominioAndActivoTrue(dominio).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese dominio ya está registrado por otra tienda");
        }
        if (adminUserRepository.findByEmail(adminEmail).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario administrador con ese correo");
        }
        if (tiendaRepo.existsByNit(nit)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una tienda registrada con ese NIT");
        }
    }

    private String normalizarDominio(String dominio) {
        String d = dominio.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^https?://", "").replaceFirst("/+$", "");
        int slash = d.indexOf('/');
        return slash >= 0 ? d.substring(0, slash) : d;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
