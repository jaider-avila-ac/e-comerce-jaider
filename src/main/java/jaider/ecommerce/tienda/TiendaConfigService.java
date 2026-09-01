package jaider.ecommerce.tienda;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.sucursal.Sucursal;
import jaider.ecommerce.sucursal.SucursalRepository;
import jaider.ecommerce.tienda.integracion.TenantIntegrationResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TiendaConfigService {

    private static final java.util.Set<String> MODOS_ENVIO_VALIDOS = java.util.Set.of("contra_entrega", "fijo", "envia");
    private static final java.util.Set<String> AMBIENTES_ENVIA_VALIDOS = java.util.Set.of("sandbox", "produccion");

    private final TiendaRepository repo;
    private final TenantIntegrationResolver integrationResolver;
    private final SucursalRepository sucursalRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public TiendaConfigResponse getConfig() {
        return toResponse(currentTienda());
    }

    @Transactional
    public TiendaConfigResponse updateConfig(TiendaConfigRequest req) {
        Tienda tienda = currentTienda();

        if (req.envioModo() != null) {
            String modo = req.envioModo().trim();
            if (!MODOS_ENVIO_VALIDOS.contains(modo)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Modo de envío inválido: " + modo);
            }
            // PLAN_INTEGRACION_ENVIA.md, Fase 3: activar 'envia' exige que ya exista lo que el
            // cálculo real necesita — nunca lo dejamos pasar "a medias" (checkout se rompería
            // para el primer cliente que compre). Se valida acá, no solo en el checkout.
            if ("envia".equals(modo)) {
                validarListaParaEnvia(tienda.getId());
            }
            tienda.setEnvioModo(modo);
        }
        if (req.envioGratisActivo() != null) {
            tienda.setEnvioGratisActivo(req.envioGratisActivo());
        }
        if (req.envioGratisDesde() != null) {
            if (req.envioGratisDesde() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El monto no puede ser negativo");
            }
            tienda.setEnvioGratisDesdeCentavos(req.envioGratisDesde() * 100L);
        }
        if (req.envioCosto() != null) {
            if (req.envioCosto() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El costo de envío no puede ser negativo");
            }
            tienda.setEnvioCostoCentavos(req.envioCosto() * 100L);
        }
        if (req.dominioStaff() != null) {
            String dominio = req.dominioStaff().trim().toLowerCase();
            if (dominio.startsWith("@")) dominio = dominio.substring(1);
            tienda.setDominioStaff(dominio.isBlank() ? null : dominio);
        }
        if (req.emailNotificacionPedidos() != null) {
            String email = req.emailNotificacionPedidos().trim();
            if (!email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Correo inválido");
            }
            tienda.setEmailNotificacionPedidos(email.isBlank() ? null : email);
        }
        if (req.emailContacto() != null) {
            String email = req.emailContacto().trim();
            if (!email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Correo de contacto inválido");
            }
            tienda.setEmailContacto(email.isBlank() ? null : email);
        }
        if (req.razonSocial() != null) {
            String v = req.razonSocial().trim();
            tienda.setRazonSocial(v.isBlank() ? null : v);
        }
        if (req.nit() != null) {
            String v = req.nit().trim();
            tienda.setNit(v.isBlank() ? null : v);
        }
        if (req.colorPrincipal() != null) {
            String v = req.colorPrincipal().trim();
            if (!v.isBlank() && !v.matches("^#[0-9A-Fa-f]{6}$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El color debe ser hexadecimal de 6 dígitos, ej. #1A2B3C");
            }
            tienda.setColorPrincipal(v.isBlank() ? null : v);
        }
        if (req.enviaAmbiente() != null) {
            String v = req.enviaAmbiente().trim().toLowerCase();
            if (!AMBIENTES_ENVIA_VALIDOS.contains(v)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ambiente de Envia inválido: " + v);
            }
            tienda.setEnviaAmbiente(v);
        }

        repo.save(tienda);
        return toResponse(tienda);
    }

    // PLAN_INTEGRACION_ENVIA.md, Fase 3 — activar 'envia' exige que ya exista lo que el cálculo
    // real va a necesitar: credenciales de Envia configuradas y al menos una sucursal activa con
    // su dirección de origen completa (de dónde recoge la transportadora). Sin esto, el checkout
    // fallaría para el primer cliente que compre con este modo activo.
    private void validarListaParaEnvia(Long tndId) {
        try {
            integrationResolver.envioCredentials(tndId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede activar el envío con Envia: falta configurar el token de Envia para esta tienda");
        }

        // sucursales sí tiene RLS forzado (a diferencia de tiendas) — hay que fijar el tenant en
        // la sesión antes de consultarla o la lista vuelve vacía en silencio.
        tenantSupport.requireTenant(em);
        boolean haySucursalConOrigen = sucursalRepository.findByActivoTrueOrderByNombreAsc().stream()
                .anyMatch(this::tieneOrigenCompleto);
        if (!haySucursalConOrigen) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede activar el envío con Envia: ninguna sucursal activa tiene una dirección de origen completa");
        }

        // Corrección de auditoría (2026-09-01): antes se podía activar 'envia' sin revisar esto,
        // y el PRIMER cliente que agregara al carrito uno de esos productos se topaba con un
        // fallo real al calcular el envío (PaqueteCalculoService rechaza cualquier producto sin
        // empaque asignado) — justo lo que esta validación entera existe para evitar.
        long productosSinEmpaque = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM productos WHERE prd_activo = true AND prd_empaque_id IS NULL")
                .getSingleResult()).longValue();
        if (productosSinEmpaque > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede activar el envío con Envia: " + productosSinEmpaque
                            + " producto(s) activo(s) todavía no tienen un empaque asignado");
        }
    }

    private boolean tieneOrigenCompleto(Sucursal s) {
        return noBlank(s.getEnvioOrigenNombre())
                && noBlank(s.getEnvioOrigenTelefono())
                && noBlank(s.getEnvioOrigenDireccion())
                && noBlank(s.getEnvioOrigenDepartamento())
                && noBlank(s.getEnvioOrigenMunicipio())
                && noBlank(s.getEnvioOrigenCodigoPostal());
    }

    private boolean noBlank(String v) {
        return v != null && !v.isBlank();
    }

    private Tienda currentTienda() {
        String tndId = TenantContext.get();
        if (tndId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        }
        return repo.findById(Long.parseLong(tndId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
    }

    private TiendaConfigResponse toResponse(Tienda tienda) {
        return new TiendaConfigResponse(
                tienda.getEnvioModo(),
                tienda.isEnvioGratisActivo(),
                tienda.getEnvioGratisDesdeCentavos() / 100L,
                tienda.getEnvioCostoCentavos() / 100L,
                tienda.getDominioStaff(),
                tienda.getEmailNotificacionPedidos(),
                tienda.getRazonSocial(),
                tienda.getNit(),
                tienda.getEmailContacto(),
                tienda.getColorPrincipal(),
                tienda.getEnviaAmbiente()
        );
    }
}
