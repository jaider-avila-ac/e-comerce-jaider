package jaider.ecommerce.tienda.integracion;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.auth.admin.AdminUser;
import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.tienda.secretos.SecretEncryptionService;
import jaider.ecommerce.tienda.secretos.TenantSecretCache;
import jaider.ecommerce.tienda.secretos.TiendaSecreto;
import jaider.ecommerce.tienda.secretos.TiendaSecretoRepository;
import jaider.ecommerce.tienda.superadmin.CampoEstadoResponse;
import jaider.ecommerce.tienda.superadmin.WompiCredencialesRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Credenciales de Wompi de UNA tienda — las puede guardar el superadmin (durante el
 * aprovisionamiento, ver SuperadminTiendaService, que delega acá) o el propio admin de esa
 * tienda (ver TiendaWompiController). Decisión explícita del usuario (2026-10-15): a diferencia
 * de Resend/Cloudinary/Envia, Wompi es la única integración que cada tienda gestiona ella misma
 * — la cuenta de Wompi es del comerciante, no de la plataforma.
 *
 * Cualquier campo que llegue en blanco/null se ignora (nunca borra lo que ya había guardado) —
 * así cualquiera de los dos puede rotar una sola llave sin reenviar las demás.
 */
@Service
@RequiredArgsConstructor
public class TiendaWompiCredencialesService {

    private static final String PROVEEDOR = "WOMPI";
    private static final String[] CAMPOS = {"PUBLIC_KEY", "PRIVATE_KEY", "INTEGRITY_KEY", "EVENTS_KEY"};

    private final TiendaRepository tiendaRepo;
    private final TiendaSecretoRepository secretoRepo;
    private final SecretEncryptionService encryption;
    private final TenantSecretCache secretCache;
    private final AdminUserRepository adminUserRepository;
    private final AuditoriaService auditoriaService;
    private final TenantSupport tenantSupport;
    private final Environment environment;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<CampoEstadoResponse> estado(Long tndId) {
        Tienda tienda = tiendaObligatoria(tndId);
        return Arrays.stream(CAMPOS).map(campo -> estadoDeUnCampo(tndId, tienda.getSecretAlias(), campo)).toList();
    }

    /** origen: "superadmin" | "tienda_admin" — solo para distinguir en la auditoría quién hizo el
     *  cambio, nunca afecta el resultado. */
    @Transactional
    public List<CampoEstadoResponse> guardar(Long tndId, WompiCredencialesRequest req, Long adminId, String origen) {
        tiendaObligatoria(tndId);

        Map<String, String> campos = new LinkedHashMap<>();
        ponerSiNoBlank(campos, "PUBLIC_KEY", req.publicKey());
        ponerSiNoBlank(campos, "PRIVATE_KEY", req.privateKey());
        ponerSiNoBlank(campos, "INTEGRITY_KEY", req.integrityKey());
        ponerSiNoBlank(campos, "EVENTS_KEY", req.eventsKey());

        if (!campos.isEmpty()) {
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
                            .setParameter("proveedor", PROVEEDOR)
                            .setParameter("campo", campo.getKey())
                            .setParameter("valor", cifrado)
                            .setParameter("adminId", adminId)
                            .executeUpdate();
                }
                secretCache.invalidar(tndId, PROVEEDOR);
                // Auditoría: SOLO qué campos cambiaron, nunca sus valores.
                auditoriaService.registrar(tndId, adminId, origen + ".wompi_actualizado",
                        "tienda_secretos", tndId, Map.of("campos", campos.keySet()));
            } finally {
                TenantContext.clear();
            }
        }
        return estado(tndId);
    }

    private void ponerSiNoBlank(Map<String, String> campos, String clave, String valor) {
        if (valor != null && !valor.isBlank()) campos.put(clave, valor);
    }

    private CampoEstadoResponse estadoDeUnCampo(Long tndId, String alias, String campo) {
        var enBd = secretoRepo.findByTndIdAndProveedorAndCampo(tndId, PROVEEDOR, campo);
        if (enBd.isPresent()) {
            TiendaSecreto s = enBd.get();
            String emailActor = s.getActualizadoPor() != null
                    ? adminUserRepository.findById(s.getActualizadoPor()).map(AdminUser::getEmail).orElse(null)
                    : null;
            return new CampoEstadoResponse(campo, true, "BD", s.getActualizadoEn(), emailActor);
        }
        // Respaldo por variable de entorno — tiendas configuradas antes de este panel (ej.
        // Calzacaribe), ver TenantIntegrationResolver.
        String desdeEnv = environment.getProperty(PROVEEDOR + "_" + alias + "_" + campo);
        if (desdeEnv != null && !desdeEnv.isBlank()) {
            return new CampoEstadoResponse(campo, true, "ENV", null, null);
        }
        return new CampoEstadoResponse(campo, false, null, null, null);
    }

    private Tienda tiendaObligatoria(Long tndId) {
        return tiendaRepo.findById(tndId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
    }
}
