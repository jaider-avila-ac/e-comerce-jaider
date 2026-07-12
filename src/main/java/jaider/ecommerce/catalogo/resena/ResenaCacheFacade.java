package jaider.ecommerce.catalogo.resena;

import com.fasterxml.jackson.core.type.TypeReference;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Envuelve la lectura pública de reseñas con caché — mismo patrón que PublicCatalogFacade. */
@Service
@RequiredArgsConstructor
public class ResenaCacheFacade {

    private final ResenaCacheService cache;
    private final ResenaService service;

    public ResenaListResponse listarPublicas(Long prdId) {
        String tnd = TenantContext.get();
        long v = cache.currentVersion(tnd);
        return cache.getOrLoad(
                cache.key(tnd, v, "producto", String.valueOf(prdId)),
                Duration.ofMinutes(10),
                () -> service.listarPublicas(prdId),
                new TypeReference<>() {}
        );
    }
}
