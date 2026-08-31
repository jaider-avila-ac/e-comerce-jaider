package jaider.ecommerce.tienda.superadmin;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.auth.admin.AdminUser;
import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaDominioRepository;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningRequest;
import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningResult;
import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningService;
import jaider.ecommerce.tienda.integracion.IntegracionSalud;
import jaider.ecommerce.tienda.integracion.TenantIntegrationHealthService;
import jaider.ecommerce.tienda.secretos.SecretEncryptionService;
import jaider.ecommerce.tienda.secretos.TenantSecretCache;
import jaider.ecommerce.tienda.secretos.TiendaSecreto;
import jaider.ecommerce.tienda.secretos.TiendaSecretoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Servicio detrás del futuro panel de superadmin (React, todavía no construido — 2026-08-31) para
 * crear tiendas y configurar sus credenciales de integración. Reutiliza al máximo lo que ya
 * existe: {@link TenantProvisioningService} para crear la tienda+admin, y
 * {@link TenantIntegrationHealthService} para probarlas en vivo sin cobrar/enviar/subir nada real.
 *
 * Lo NUEVO acá es el guardado de credenciales cifradas en {@code tienda_secretos} — decisión
 * explícita del usuario de que estas SÍ vivan en la BD (a diferencia de las de tiendas ya
 * configuradas por variable de entorno, que TenantIntegrationResolver sigue soportando como
 * respaldo). Ningún método de esta clase devuelve jamás el valor de una credencial — solo si
 * está "configurada" y quién/cuándo la guardó.
 */
@Service
@RequiredArgsConstructor
public class SuperadminTiendaService {

    private final TiendaRepository tiendaRepo;
    private final TiendaDominioRepository dominioRepo;
    private final AdminUserRepository adminUserRepository;
    private final TenantProvisioningService provisioningService;
    private final TenantIntegrationHealthService healthService;
    private final TiendaSecretoRepository secretoRepo;
    private final SecretEncryptionService encryption;
    private final TenantSecretCache secretCache;
    private final AuditoriaService auditoriaService;
    private final TenantSupport tenantSupport;
    private final Environment environment;

    @Value("${app.base-url}")
    private String appBaseUrl;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public TenantProvisioningResult crearBorrador(CrearTiendaRequest req) {
        String alias = derivarAlias(req.slug());
        TenantProvisioningRequest provisioningReq = new TenantProvisioningRequest(
                req.nombreComercial(), req.razonSocial(), req.nit(), req.slug(), req.dominioPrincipal(),
                req.emailContacto(), req.emailNotificacionPedidos(), req.whatsapp(), alias,
                req.adminEmail(), req.adminPassword(), req.adminNombre(),
                req.envioModo(), req.envioCostoCentavos(), req.envioGratisActivo(), req.envioGratisDesdeCentavos()
        );
        return provisioningService.provisionar(provisioningReq);
    }

    @Transactional(readOnly = true)
    public List<TiendaResumenResponse> listar() {
        return tiendaRepo.findAll().stream()
                .map(t -> new TiendaResumenResponse(
                        t.getId(), t.getNombre(), t.getSlug(), t.isActivo(),
                        dominioRepo.findByTndIdAndPrincipalTrueAndActivoTrue(t.getId())
                                .map(d -> d.getDominio()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public TiendaDetalleResponse detalle(Long tndId) {
        Tienda tienda = tiendaObligatoria(tndId);
        String dominio = dominioRepo.findByTndIdAndPrincipalTrueAndActivoTrue(tndId)
                .map(d -> d.getDominio()).orElse(null);
        return new TiendaDetalleResponse(
                tienda.getId(), tienda.getNombre(), tienda.getSlug(), tienda.isActivo(), dominio,
                estadoCampos(tndId, tienda.getSecretAlias(), "WOMPI", "PUBLIC_KEY", "PRIVATE_KEY", "INTEGRITY_KEY", "EVENTS_KEY"),
                estadoCampos(tndId, tienda.getSecretAlias(), "RESEND", "API_KEY", "FROM"),
                estadoCampos(tndId, tienda.getSecretAlias(), "CLOUDINARY", "CLOUD_NAME", "API_KEY", "API_SECRET"),
                appBaseUrl + "/api/v1/public/pagos/webhook/wompi"
        );
    }

    @Transactional
    public List<CampoEstadoResponse> guardarWompi(Long tndId, WompiCredencialesRequest req, Long adminId) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("PUBLIC_KEY", req.publicKey());
        campos.put("INTEGRITY_KEY", req.integrityKey());
        campos.put("EVENTS_KEY", req.eventsKey());
        if (req.privateKey() != null && !req.privateKey().isBlank()) campos.put("PRIVATE_KEY", req.privateKey());
        guardarCampos(tndId, "WOMPI", campos, adminId);
        Tienda tienda = tiendaObligatoria(tndId);
        return estadoCampos(tndId, tienda.getSecretAlias(), "WOMPI", "PUBLIC_KEY", "PRIVATE_KEY", "INTEGRITY_KEY", "EVENTS_KEY");
    }

    @Transactional
    public List<CampoEstadoResponse> guardarResend(Long tndId, ResendCredencialesRequest req, Long adminId) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("API_KEY", req.apiKey());
        campos.put("FROM", req.from());
        guardarCampos(tndId, "RESEND", campos, adminId);
        Tienda tienda = tiendaObligatoria(tndId);
        return estadoCampos(tndId, tienda.getSecretAlias(), "RESEND", "API_KEY", "FROM");
    }

    @Transactional
    public List<CampoEstadoResponse> guardarCloudinary(Long tndId, CloudinaryCredencialesRequest req, Long adminId) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("CLOUD_NAME", req.cloudName());
        campos.put("API_KEY", req.apiKey());
        campos.put("API_SECRET", req.apiSecret());
        guardarCampos(tndId, "CLOUDINARY", campos, adminId);
        Tienda tienda = tiendaObligatoria(tndId);
        return estadoCampos(tndId, tienda.getSecretAlias(), "CLOUDINARY", "CLOUD_NAME", "API_KEY", "API_SECRET");
    }

    @Transactional(readOnly = true)
    public List<IntegracionSalud> verificar(Long tndId) {
        tiendaObligatoria(tndId);
        return healthService.chequear(tndId);
    }

    @Transactional
    public void activar(Long tndId) {
        Tienda tienda = tiendaObligatoria(tndId);
        List<IntegracionSalud> salud = healthService.chequear(tndId);
        boolean saludOk = salud.stream().allMatch(IntegracionSalud::ok);
        if (!saludOk) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede activar: hay integraciones sin configurar o fallando. Verifica primero.");
        }
        tienda.setActivo(true);
        tiendaRepo.save(tienda);
    }

    @Transactional
    public void desactivar(Long tndId) {
        Tienda tienda = tiendaObligatoria(tndId);
        tienda.setActivo(false);
        tiendaRepo.save(tienda);
    }

    // ── Internos ───────────────────────────────────────────────────────────

    private void guardarCampos(Long tndId, String proveedor, Map<String, String> campos, Long adminId) {
        tiendaObligatoria(tndId); // 404 antes que nada si la tienda no existe

        TenantContext.set(tndId.toString());
        try {
            tenantSupport.requireTenant(em); // auditoria_acciones tiene RLS

            for (Map.Entry<String, String> campo : campos.entrySet()) {
                String cifrado = encryption.encrypt(campo.getValue());
                em.createNativeQuery("""
                        INSERT INTO tienda_secretos (tse_tnd_id, tse_proveedor, tse_campo, tse_valor_cifrado, tse_actualizado_por)
                        VALUES (:tndId, :proveedor, :campo, :valor, :adminId)
                        ON CONFLICT (tse_tnd_id, tse_proveedor, tse_campo)
                        DO UPDATE SET tse_valor_cifrado = EXCLUDED.tse_valor_cifrado,
                                      tse_actualizado_en = now(),
                                      tse_actualizado_por = EXCLUDED.tse_actualizado_por
                        """)
                        .setParameter("tndId", tndId)
                        .setParameter("proveedor", proveedor)
                        .setParameter("campo", campo.getKey())
                        .setParameter("valor", cifrado)
                        .setParameter("adminId", adminId)
                        .executeUpdate();
            }
            secretCache.invalidar(tndId, proveedor);

            // Auditoría: SOLO qué campos cambiaron, nunca sus valores.
            auditoriaService.registrar(tndId, adminId, "superadmin.credenciales_actualizadas",
                    "tienda_secretos", tndId,
                    Map.of("proveedor", proveedor, "campos", campos.keySet()));
        } finally {
            TenantContext.clear();
        }
    }

    private List<CampoEstadoResponse> estadoCampos(Long tndId, String alias, String proveedor, String... campos) {
        return Arrays.stream(campos)
                .map(campo -> estadoDeUnCampo(tndId, alias, proveedor, campo))
                .toList();
    }

    private CampoEstadoResponse estadoDeUnCampo(Long tndId, String alias, String proveedor, String campo) {
        var enBd = secretoRepo.findByTndIdAndProveedorAndCampo(tndId, proveedor, campo);
        if (enBd.isPresent()) {
            TiendaSecreto s = enBd.get();
            String emailActor = s.getActualizadoPor() != null
                    ? adminUserRepository.findById(s.getActualizadoPor()).map(AdminUser::getEmail).orElse(null)
                    : null;
            return new CampoEstadoResponse(campo, true, "BD", s.getActualizadoEn(), emailActor);
        }
        String desdeEnv = environment.getProperty(proveedor + "_" + alias + "_" + campo);
        if (desdeEnv != null && !desdeEnv.isBlank()) {
            return new CampoEstadoResponse(campo, true, "ENV", null, null);
        }
        return new CampoEstadoResponse(campo, false, null, null, null);
    }

    private Tienda tiendaObligatoria(Long tndId) {
        return tiendaRepo.findById(tndId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
    }

    /** Deriva un alias de secretos válido (mayúsculas/números/guion bajo) desde el slug — en este
     *  flujo el alias ya casi no importa (las credenciales van a la BD, no a variables de
     *  entorno), pero la columna sigue siendo NOT NULL UNIQUE, así que hace falta algo. */
    private String derivarAlias(String slug) {
        return slug.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }
}
