package jaider.ecommerce.auth.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Lista negra de tokens invalidados por logout, en Redis con TTL = tiempo restante del token
 * (así las entradas expiran solas y nunca crecen sin límite). No se guarda el token en claro,
 * solo su hash, como clave.
 *
 * Si Redis falla, isBlacklisted() responde false (falla abierto) — igual que el resto del
 * caché de este proyecto (CatalogCacheService): un problema de Redis nunca debe dejar a todos
 * los usuarios sin poder usar la app.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final StringRedisTemplate redis;

    public void blacklist(String token, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) return; // ya venció por su cuenta, no hace falta guardarlo
        try {
            redis.opsForValue().set(key(token), "1", ttl);
        } catch (Exception e) {
            log.warn("No se pudo invalidar el token en Redis: {}", e.getMessage());
        }
    }

    public boolean isBlacklisted(String token) {
        try {
            return redis.hasKey(key(token));
        } catch (Exception e) {
            log.warn("No se pudo consultar la lista negra de tokens en Redis: {}", e.getMessage());
            return false;
        }
    }

    private String key(String token) {
        return "logout:jwt:" + sha256(token);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 no disponible", e);
        }
    }
}
