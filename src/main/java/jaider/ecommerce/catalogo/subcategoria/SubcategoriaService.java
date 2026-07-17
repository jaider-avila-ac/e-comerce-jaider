package jaider.ecommerce.catalogo.subcategoria;

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
public class SubcategoriaService {

    private final SubcategoriaRepository repo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    private static String slugify(String text) {
        return text.toLowerCase()
                .replace("á", "a").replace("à", "a").replace("ä", "a")
                .replace("é", "e").replace("è", "e").replace("ë", "e")
                .replace("í", "i").replace("ì", "i").replace("ï", "i")
                .replace("ó", "o").replace("ò", "o").replace("ö", "o")
                .replace("ú", "u").replace("ù", "u").replace("ü", "u")
                .replace("ñ", "n")
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
    }

    @Transactional(readOnly = true)
    public List<SubcategoriaResponse> getAll() {
        tenantSupport.applyTenant(em);
        return repo.findAllByOrderByOrdenAscNombreAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SubcategoriaResponse> getByCat(Long catId) {
        tenantSupport.applyTenant(em);
        return repo.findByCatIdOrderByOrdenAscNombreAsc(catId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SubcategoriaResponse getById(Long id) {
        tenantSupport.applyTenant(em);
        Subcategoria sub = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subcategoría no encontrada: " + id));
        return toResponse(sub);
    }

    @Transactional
    public SubcategoriaResponse create(SubcategoriaRequest req) {
        tenantSupport.applyTenant(em);

        // Auto-asigna el orden al final de las subcategorías de esta misma categoría —
        // el usuario reordena después con los botones arriba/abajo (ver reordenar()).
        Number maxOrden = (Number) em.createNativeQuery(
                "SELECT COALESCE(MAX(sub_orden), -1) FROM subcategorias WHERE sub_cat_id = :catId")
                .setParameter("catId", req.catId())
                .getSingleResult();
        short nuevoOrden = (short) (maxOrden.intValue() + 1);

        Subcategoria sub = new Subcategoria();
        sub.setCatId(req.catId());
        sub.setNombre(req.nombre());
        sub.setSlug(req.slug() != null && !req.slug().isBlank() ? req.slug() : slugify(req.nombre()));
        sub.setOrden(nuevoOrden);
        sub.setActivo(req.activo() == null || req.activo());
        return toResponse(repo.save(sub));
    }

    @Transactional
    public SubcategoriaResponse update(Long id, SubcategoriaRequest req) {
        tenantSupport.applyTenant(em);
        Subcategoria sub = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subcategoría no encontrada: " + id));
        if (req.nombre() != null && !req.nombre().isBlank()) sub.setNombre(req.nombre());
        if (req.slug() != null && !req.slug().isBlank()) sub.setSlug(req.slug());
        if (req.orden() != null) sub.setOrden(req.orden());
        if (req.activo() != null) sub.setActivo(req.activo());
        return toResponse(repo.save(sub));
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.applyTenant(em);
        if (!repo.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subcategoría no encontrada: " + id);
        repo.deleteById(id);
    }

    /** Reordena libremente (arriba/abajo) las subcategorías de una misma categoría — el
     *  frontend manda la lista completa de ids de esa categoría en el nuevo orden. */
    @Transactional
    public void reordenar(List<Long> ids) {
        tenantSupport.applyTenant(em);
        for (int i = 0; i < ids.size(); i++) {
            em.createNativeQuery("UPDATE subcategorias SET sub_orden = :orden WHERE sub_id = :id")
                    .setParameter("orden", (short) i)
                    .setParameter("id", ids.get(i))
                    .executeUpdate();
        }
    }

    private SubcategoriaResponse toResponse(Subcategoria s) {
        return new SubcategoriaResponse(
                s.getId(), s.getCatId(), s.getNombre(),
                s.getSlug(), s.getOrden(), s.isActivo()
        );
    }
}
