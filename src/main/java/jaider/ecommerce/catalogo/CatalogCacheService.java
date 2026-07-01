package jaider.ecommerce.catalogo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogCacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * Lee del caché o ejecuta el loader, serializa el resultado como JSON string y lo almacena.
     * Si Redis falla (caído, timeout), el fallback es transparente: siempre retorna un valor.
     */
    public <T> T getOrLoad(String key, Duration ttl, Supplier<T> loader, TypeReference<T> typeRef) {
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, typeRef);
            }
        } catch (Exception e) {
            log.warn("Cache read miss or error — key={}: {}", key, e.getMessage());
        }

        T value = loader.get();

        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Cache write skipped — key={}: {}", key, e.getMessage());
        }

        return value;
    }

    /** Versión actual del catálogo del tenant. Retorna 0 si la clave no existe o Redis falla. */
    public long currentVersion(String tenantId) {
        try {
            String v = redis.opsForValue().get(versionKey(tenantId));
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Incrementa el contador de versión del tenant — O(1), atómico.
     * Todas las claves de la versión anterior quedan huérfanas y expiran por TTL.
     */
    public void invalidate(String tenantId) {
        if (tenantId == null) return;
        try {
            redis.opsForValue().increment(versionKey(tenantId));
        } catch (Exception e) {
            log.warn("Cache invalidation failed — tenant={}: {}", tenantId, e.getMessage());
        }
    }

    /** Construye una clave con formato: catalog:{tenantId}:{version}:{segments...} */
    public String key(String tenantId, long version, String... segments) {
        return "catalog:" + tenantId + ":" + version + ":" + String.join(":", segments);
    }

    private String versionKey(String tenantId) {
        return "catalog:" + tenantId + ":v";
    }
}
