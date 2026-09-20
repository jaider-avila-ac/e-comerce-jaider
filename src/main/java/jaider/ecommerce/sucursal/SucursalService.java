package jaider.ecommerce.sucursal;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SucursalService {

    private final SucursalRepository sucursalRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    // Lista liviana para selectores (alta de colaborador, filtros de pedidos/reportes) —
    // cualquier miembro del staff autenticado puede consultarla, igual que
    // PedidoService.listarColaboradores(). RLS ya restringe al tenant actual.
    @Transactional(readOnly = true)
    public List<SucursalResponse> listar() {
        tenantSupport.requireTenant(em);
        return sucursalRepository.findByActivoTrueOrderByNombreAsc().stream()
                .map(s -> new SucursalResponse(s.getId(), s.getNombre(), s.getWhatsapp(),
                        s.getEnvioOrigenNombre(), s.getEnvioOrigenTelefono(), s.getEnvioOrigenDireccion(),
                        s.getEnvioOrigenComplemento(), s.getEnvioOrigenDepartamento(), s.getEnvioOrigenMunicipio(),
                        s.getEnvioOrigenCodigoPostal()))
                .toList();
    }

    /** Edita una sucursal YA existente (crear una nueva es exclusivo del superadmin — ver
     *  SucursalUpdateRequest). Sin campos obligatorios: cada uno se aplica solo si viene, igual
     *  que TiendaConfigService.updateConfig(). RLS ya impide que un admin edite una sucursal de
     *  otra tienda (findById no la encontraría bajo esa política). */
    @Transactional
    public SucursalResponse actualizar(Long id, SucursalUpdateRequest req) {
        tenantSupport.requireTenant(em);
        Sucursal s = sucursalRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sucursal no encontrada"));

        if (req.nombre() != null) {
            String v = req.nombre().trim();
            if (v.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre no puede quedar vacío");
            s.setNombre(v);
        }
        if (req.whatsapp() != null) {
            String v = req.whatsapp().trim();
            s.setWhatsapp(v.isBlank() ? null : v);
        }
        // Corrección de auditoría (2026-09-01, tercera vuelta): valida el estado RESULTANTE
        // (activo + origen) ANTES de tocar la entidad — vaciar el origen o desactivar esta
        // sucursal, en una tienda ya en modo 'envia', puede dejar CERO sucursales activas con
        // dirección de origen completa (el checkout se rompe para el primer cliente que compre,
        // ver EnvioCotizacionService.cargarDireccionOrigen). Solo bloquea si esta era de verdad
        // la ÚLTIMA con origen completo; otra sucursal activa con origen completo sigue
        // permitiendo la edición sin problema. Se valida sobre variables locales, nunca sobre la
        // entidad ya mutada, para no dejarla a medio cambiar en memoria si esto lanza.
        boolean activoResultante = req.activo() != null ? req.activo() : s.isActivo();
        boolean origenCompletoResultante = tieneOrigenCompleto(
                req.envioOrigenNombre() != null ? blankToNull(req.envioOrigenNombre()) : s.getEnvioOrigenNombre(),
                req.envioOrigenTelefono() != null ? blankToNull(req.envioOrigenTelefono()) : s.getEnvioOrigenTelefono(),
                req.envioOrigenDireccion() != null ? blankToNull(req.envioOrigenDireccion()) : s.getEnvioOrigenDireccion(),
                req.envioOrigenMunicipio() != null ? blankToNull(req.envioOrigenMunicipio()) : s.getEnvioOrigenMunicipio(),
                req.envioOrigenDepartamento() != null ? blankToNull(req.envioOrigenDepartamento()) : s.getEnvioOrigenDepartamento(),
                req.envioOrigenCodigoPostal() != null ? blankToNull(req.envioOrigenCodigoPostal()) : s.getEnvioOrigenCodigoPostal());
        if (!(activoResultante && origenCompletoResultante) && tiendaEnModoEnvia()
                && sucursalRepository.findByActivoTrueOrderByNombreAsc().stream()
                        .noneMatch(otra -> !otra.getId().equals(s.getId()) && tieneOrigenCompleto(otra))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esta tienda calcula el envío real y necesita al menos una sucursal activa con dirección de origen completa — completa otra antes de dejar esta incompleta o inactiva");
        }

        if (req.activo() != null) {
            s.setActivo(req.activo());
        }
        if (req.envioOrigenNombre() != null) s.setEnvioOrigenNombre(blankToNull(req.envioOrigenNombre()));
        if (req.envioOrigenTelefono() != null) s.setEnvioOrigenTelefono(blankToNull(req.envioOrigenTelefono()));
        if (req.envioOrigenDireccion() != null) s.setEnvioOrigenDireccion(blankToNull(req.envioOrigenDireccion()));
        if (req.envioOrigenComplemento() != null) s.setEnvioOrigenComplemento(blankToNull(req.envioOrigenComplemento()));
        if (req.envioOrigenDepartamento() != null) s.setEnvioOrigenDepartamento(blankToNull(req.envioOrigenDepartamento()));
        if (req.envioOrigenMunicipio() != null) s.setEnvioOrigenMunicipio(blankToNull(req.envioOrigenMunicipio()));
        if (req.envioOrigenCodigoPostal() != null) s.setEnvioOrigenCodigoPostal(blankToNull(req.envioOrigenCodigoPostal()));

        sucursalRepository.save(s);
        return new SucursalResponse(s.getId(), s.getNombre(), s.getWhatsapp(),
                s.getEnvioOrigenNombre(), s.getEnvioOrigenTelefono(), s.getEnvioOrigenDireccion(),
                s.getEnvioOrigenComplemento(), s.getEnvioOrigenDepartamento(), s.getEnvioOrigenMunicipio(),
                s.getEnvioOrigenCodigoPostal());
    }

    private String blankToNull(String v) {
        String trimmed = v.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private boolean tieneOrigenCompleto(Sucursal s) {
        return tieneOrigenCompleto(s.getEnvioOrigenNombre(), s.getEnvioOrigenTelefono(),
                s.getEnvioOrigenDireccion(), s.getEnvioOrigenMunicipio(),
                s.getEnvioOrigenDepartamento(), s.getEnvioOrigenCodigoPostal());
    }

    private boolean tieneOrigenCompleto(String nombre, String telefono, String direccion,
                                         String municipio, String departamento, String codigoPostal) {
        return noBlank(nombre) && noBlank(telefono) && noBlank(direccion)
                && noBlank(municipio) && noBlank(departamento) && noBlank(codigoPostal);
    }

    private boolean noBlank(String v) {
        return v != null && !v.isBlank();
    }

    private boolean tiendaEnModoEnvia() {
        String tndId = TenantContext.get();
        if (tndId == null) return false;
        Object modo = em.createNativeQuery("SELECT tnd_envio_modo FROM tiendas WHERE tnd_id = :tndId")
                .setParameter("tndId", Long.parseLong(tndId))
                .getSingleResult();
        return "envia".equals(modo);
    }
}
