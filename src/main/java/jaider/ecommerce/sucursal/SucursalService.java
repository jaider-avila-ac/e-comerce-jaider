package jaider.ecommerce.sucursal;

import jaider.ecommerce.shared.TenantSupport;
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
}
