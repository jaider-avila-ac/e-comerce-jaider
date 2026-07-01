package jaider.ecommerce.catalogo.coleccion;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.catalogo.producto.ProductoImagenRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ColeccionService {

    private final ColeccionRepository repo;
    private final ProductoImagenRepository imagenRepo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<ColeccionResponse> getAll() {
        tenantSupport.applyTenant(em);
        return repo.findAllByOrderByOrdenAscNombreAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ColeccionResponse getById(Long id) {
        tenantSupport.applyTenant(em);
        Coleccion c = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Colección no encontrada"));
        return toResponse(c);
    }

    @Transactional
    public ColeccionResponse create(ColeccionRequest req) {
        tenantSupport.applyTenant(em);
        if (req.nombre() == null || req.nombre().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        if (req.slug() == null || req.slug().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El slug es obligatorio");
        if (repo.existsBySlug(req.slug().trim()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una colección con ese slug");

        Coleccion c = new Coleccion();
        c.setTndId(Long.parseLong(TenantContext.get()));
        applyRequest(c, req);
        c = repo.save(c);
        saveProductos(c.getId(), req.productoIds());
        return toResponse(c);
    }

    @Transactional
    public ColeccionResponse update(Long id, ColeccionRequest req) {
        tenantSupport.applyTenant(em);
        Coleccion c = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Colección no encontrada"));
        applyRequest(c, req);
        c = repo.save(c);
        if (req.productoIds() != null) {
            saveProductos(c.getId(), req.productoIds());
        }
        return toResponse(c);
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.applyTenant(em);
        if (!repo.existsById(id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Colección no encontrada");
        // coleccion_productos se borra por CASCADE
        repo.deleteById(id);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void applyRequest(Coleccion c, ColeccionRequest req) {
        if (req.nombre() != null) c.setNombre(req.nombre().trim());
        if (req.slug() != null)   c.setSlug(req.slug().trim());
        if (req.descripcion() != null) c.setDescripcion(req.descripcion().trim());
        if (req.activo() != null)  c.setActivo(req.activo());
        if (req.orden() != null)   c.setOrden(req.orden().shortValue());
    }

    private void saveProductos(Long colId, List<Long> productoIds) {
        em.createNativeQuery("DELETE FROM coleccion_productos WHERE col_id = :colId")
                .setParameter("colId", colId)
                .executeUpdate();
        if (productoIds == null || productoIds.isEmpty()) return;
        short ord = 0;
        for (Long prdId : productoIds) {
            em.createNativeQuery(
                    "INSERT INTO coleccion_productos (col_id, prd_id, cp_orden) " +
                    "VALUES (:colId, :prdId, :ord) ON CONFLICT DO NOTHING")
                    .setParameter("colId", colId)
                    .setParameter("prdId", prdId)
                    .setParameter("ord", ord++)
                    .executeUpdate();
        }
    }

    private ColeccionResponse toResponse(Coleccion c) {
        List<Long> prdIds = repo.findProductoIdsByColId(c.getId());
        // Imagen: primera imagen del primer producto de la colección
        String imagenUrl = prdIds.stream()
                .flatMap(prdId -> imagenRepo.findByPrdIdOrderByOrdenAscIdAsc(prdId).stream()
                        .filter(img -> "imagen".equals(img.getTipo()))
                        .map(img -> img.getUrl())
                        .limit(1))
                .findFirst()
                .orElse(null);
        return new ColeccionResponse(
                c.getId(), c.getNombre(), c.getSlug(), c.getDescripcion(),
                c.isActivo(), c.getOrden(), prdIds, imagenUrl
        );
    }
}
