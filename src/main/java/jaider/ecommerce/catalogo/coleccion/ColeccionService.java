package jaider.ecommerce.catalogo.coleccion;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.infra.CloudinaryService;
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
    private final CloudinaryService cloudinaryService;
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
    public List<Long> getProductoIds(Long colId) {
        tenantSupport.applyTenant(em);
        return repo.findProductoIdsByColId(colId);
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

        // Auto-asigna el orden al final de las colecciones existentes — el usuario reordena
        // después con los botones arriba/abajo (ver reordenar()), nunca escribiendo un número.
        Number maxOrden = (Number) em.createNativeQuery(
                "SELECT COALESCE(MAX(col_orden), -1) FROM colecciones").getSingleResult();

        Coleccion c = new Coleccion();
        c.setTndId(Long.parseLong(TenantContext.get()));
        applyRequest(c, req);
        c.setOrden((short) (maxOrden.intValue() + 1));
        c = repo.save(c);
        saveProductos(c.getId(), req.productoIds());
        return toResponse(c);
    }

    @Transactional
    public ColeccionResponse update(Long id, ColeccionRequest req) {
        tenantSupport.applyTenant(em);
        Coleccion c = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Colección no encontrada"));
        String imagenAnterior = c.getImagenUrl();
        applyRequest(c, req);
        c = repo.save(c);
        if (req.productoIds() != null) {
            saveProductos(c.getId(), req.productoIds());
        }
        if (req.imagenUrl() != null && !req.imagenUrl().equals(imagenAnterior) && imagenAnterior != null) {
            cloudinaryService.delete(imagenAnterior);
        }
        return toResponse(c);
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.applyTenant(em);
        Coleccion c = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Colección no encontrada"));
        // coleccion_productos se borra por CASCADE
        repo.deleteById(id);
        cloudinaryService.delete(c.getImagenUrl());
    }

    /** Reordena moviendo libremente (arriba/abajo) — el frontend manda la lista completa
     *  de ids en el nuevo orden, y cada posición en la lista se vuelve su "orden". */
    @Transactional
    public void reordenar(List<Long> ids) {
        tenantSupport.applyTenant(em);
        for (int i = 0; i < ids.size(); i++) {
            em.createNativeQuery("UPDATE colecciones SET col_orden = :orden WHERE col_id = :id")
                    .setParameter("orden", (short) i)
                    .setParameter("id", ids.get(i))
                    .executeUpdate();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void applyRequest(Coleccion c, ColeccionRequest req) {
        if (req.nombre() != null) c.setNombre(req.nombre().trim());
        if (req.slug() != null)   c.setSlug(req.slug().trim());
        if (req.descripcion() != null) c.setDescripcion(req.descripcion().trim());
        if (req.activo() != null)  c.setActivo(req.activo());
        if (req.orden() != null)   c.setOrden(req.orden().shortValue());
        if (req.imagenUrl() != null) c.setImagenUrl(req.imagenUrl());
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
        return new ColeccionResponse(
                c.getId(), c.getNombre(), c.getSlug(), c.getDescripcion(),
                c.isActivo(), c.getOrden(), prdIds, c.getImagenUrl()
        );
    }
}
