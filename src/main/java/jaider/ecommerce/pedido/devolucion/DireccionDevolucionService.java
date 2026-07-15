package jaider.ecommerce.pedido.devolucion;

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
public class DireccionDevolucionService {

    private final DireccionDevolucionRepository repo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<DireccionDevolucionResponse> getAll() {
        tenantSupport.applyTenant(em);
        return repo.findAllByOrderByNombreAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<DireccionDevolucionResponse> getActivas() {
        tenantSupport.applyTenant(em);
        return repo.findByActivoTrueOrderByNombreAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public DireccionDevolucionResponse create(DireccionDevolucionRequest req) {
        tenantSupport.applyTenant(em);
        String tndId = TenantContext.get();
        if (tndId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        validar(req);

        DireccionDevolucion d = new DireccionDevolucion();
        d.setTndId(Long.parseLong(tndId));
        aplicar(d, req);
        return toResponse(repo.save(d));
    }

    @Transactional
    public DireccionDevolucionResponse update(Long id, DireccionDevolucionRequest req) {
        tenantSupport.applyTenant(em);
        DireccionDevolucion d = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dirección no encontrada: " + id));
        aplicar(d, req);
        return toResponse(repo.save(d));
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.applyTenant(em);
        if (!repo.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dirección no encontrada: " + id);
        boolean enUso = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM solicitudes_devolucion WHERE sod_dvd_id = :id")
                .setParameter("id", id).getSingleResult()).longValue() > 0;
        if (enUso) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede eliminar: hay solicitudes de devolución que usan esta dirección. Desactívala en su lugar.");
        }
        repo.deleteById(id);
    }

    private void validar(DireccionDevolucionRequest req) {
        if (req.nombre() == null || req.nombre().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre de la dirección es obligatorio");
        if (req.direccion() == null || req.direccion().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La dirección es obligatoria");
        if (req.departamento() == null || req.departamento().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El departamento es obligatorio");
        if (req.municipio() == null || req.municipio().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El municipio es obligatorio");
    }

    private void aplicar(DireccionDevolucion d, DireccionDevolucionRequest req) {
        if (req.nombre() != null) d.setNombre(req.nombre().trim());
        if (req.direccion() != null) d.setDireccion(req.direccion().trim());
        if (req.complemento() != null) d.setComplemento(req.complemento().trim());
        if (req.departamento() != null) d.setDepartamento(req.departamento().trim());
        if (req.municipio() != null) d.setMunicipio(req.municipio().trim());
        if (req.barrio() != null) d.setBarrio(req.barrio().trim());
        if (req.contactoNombre() != null) d.setContactoNombre(req.contactoNombre().trim());
        if (req.contactoTelefono() != null) d.setContactoTelefono(req.contactoTelefono().trim());
        if (req.activo() != null) d.setActivo(req.activo());
    }

    private DireccionDevolucionResponse toResponse(DireccionDevolucion d) {
        return new DireccionDevolucionResponse(
                d.getId(), d.getNombre(), d.getDireccion(), d.getComplemento(), d.getDepartamento(),
                d.getMunicipio(), d.getBarrio(), d.getContactoNombre(), d.getContactoTelefono(), d.isActivo()
        );
    }
}
