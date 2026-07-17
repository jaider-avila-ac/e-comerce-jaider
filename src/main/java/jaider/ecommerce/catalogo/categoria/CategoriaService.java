package jaider.ecommerce.catalogo.categoria;

import jaider.ecommerce.catalogo.CatalogCacheService;
import jaider.ecommerce.infra.CloudinaryService;
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
public class CategoriaService {

    private final CategoriaRepository repo;
    private final TenantSupport tenantSupport;
    private final CatalogCacheService catalogCache;
    private final CloudinaryService cloudinaryService;

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
    public List<CategoriaResponse> getAll() {
        tenantSupport.applyTenant(em);
        return repo.findAllByOrderByOrdenAscNombreAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoriaResponse getById(Long id) {
        tenantSupport.applyTenant(em);
        Categoria cat = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoría no encontrada: " + id));
        return toResponse(cat);
    }

    @Transactional
    public CategoriaResponse create(CategoriaRequest req) {
        tenantSupport.applyTenant(em);
        String tndId = TenantContext.get();
        if (tndId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");

        // Auto-asigna el orden al final de las categorías existentes — el usuario
        // reordena después con los botones arriba/abajo (ver reordenar()), nunca
        // escribiendo un número.
        Number maxOrden = (Number) em.createNativeQuery(
                "SELECT COALESCE(MAX(cat_orden), -1) FROM categorias").getSingleResult();
        short nuevoOrden = (short) (maxOrden.intValue() + 1);

        Categoria cat = new Categoria();
        cat.setTndId(Long.parseLong(tndId));
        cat.setNombre(req.nombre());
        cat.setSlug(req.slug() != null && !req.slug().isBlank() ? req.slug() : slugify(req.nombre()));
        cat.setImagenUrl(req.imagenUrl());
        cat.setOrden(nuevoOrden);
        cat.setActivo(req.activo() == null || req.activo());
        CategoriaResponse resp = toResponse(repo.save(cat));
        catalogCache.invalidate(TenantContext.get());
        return resp;
    }

    @Transactional
    public CategoriaResponse update(Long id, CategoriaRequest req) {
        tenantSupport.applyTenant(em);
        Categoria cat = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoría no encontrada: " + id));
        if (req.nombre() != null && !req.nombre().isBlank()) cat.setNombre(req.nombre());
        if (req.slug() != null && !req.slug().isBlank()) cat.setSlug(req.slug());
        String imagenAnterior = cat.getImagenUrl();
        if (req.imagenUrl() != null) cat.setImagenUrl(req.imagenUrl());
        if (req.orden() != null) cat.setOrden(req.orden());
        if (req.activo() != null) cat.setActivo(req.activo());
        CategoriaResponse resp = toResponse(repo.save(cat));
        catalogCache.invalidate(TenantContext.get());

        if (req.imagenUrl() != null && !req.imagenUrl().equals(imagenAnterior)) {
            cloudinaryService.delete(imagenAnterior);
        }
        return resp;
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.applyTenant(em);
        Categoria cat = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoría no encontrada: " + id));
        repo.deleteById(id);
        catalogCache.invalidate(TenantContext.get());
        cloudinaryService.delete(cat.getImagenUrl());
    }

    /** Reordena moviendo libremente (arriba/abajo) — el frontend manda la lista completa
     *  de ids en el nuevo orden, y cada posición en la lista se vuelve su "orden". */
    @Transactional
    public void reordenar(List<Long> ids) {
        tenantSupport.applyTenant(em);
        for (int i = 0; i < ids.size(); i++) {
            em.createNativeQuery("UPDATE categorias SET cat_orden = :orden WHERE cat_id = :id")
                    .setParameter("orden", (short) i)
                    .setParameter("id", ids.get(i))
                    .executeUpdate();
        }
        catalogCache.invalidate(TenantContext.get());
    }

    private CategoriaResponse toResponse(Categoria c) {
        return new CategoriaResponse(
                c.getId(), c.getNombre(), c.getSlug(),
                c.getImagenUrl(), c.getOrden(), c.isActivo(), c.getCreadoEn()
        );
    }
}
