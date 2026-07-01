package jaider.ecommerce.catalogo.publico;

import com.fasterxml.jackson.core.type.TypeReference;
import jaider.ecommerce.catalogo.CatalogCacheService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PublicCatalogFacade {

    private final CatalogCacheService cache;
    private final PublicCatalogService service;

    public List<PublicCategoriaResponse> getCategorias() {
        String tnd = TenantContext.get();
        long v = cache.currentVersion(tnd);
        return cache.getOrLoad(
                cache.key(tnd, v, "categories"),
                Duration.ofMinutes(30),
                service::getCategorias,
                new TypeReference<>() {}
        );
    }

    public List<PublicProductoResponse> getProductos(Long catId, String q) {
        String tnd = TenantContext.get();
        long v = cache.currentVersion(tnd);
        String seg = buildProductsSegment(catId, q);
        return cache.getOrLoad(
                cache.key(tnd, v, "products", seg),
                Duration.ofMinutes(10),
                () -> service.getProductos(catId, q),
                new TypeReference<>() {}
        );
    }

    public PublicProductoResponse getProductoById(Long id) {
        String tnd = TenantContext.get();
        long v = cache.currentVersion(tnd);
        return cache.getOrLoad(
                cache.key(tnd, v, "product", String.valueOf(id)),
                Duration.ofMinutes(10),
                () -> service.getProductoById(id),
                new TypeReference<>() {}
        );
    }

    private String buildProductsSegment(Long catId, String q) {
        boolean hasCat = catId != null;
        boolean hasQ   = q != null && !q.isBlank();
        if (hasCat && hasQ)  return "cat:" + catId + ":q:" + q.toLowerCase().trim();
        if (hasCat)          return "cat:" + catId;
        if (hasQ)            return "q:" + q.toLowerCase().trim();
        return "all";
    }
}
