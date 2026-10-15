package jaider.ecommerce.tienda;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Api key propia de cada tienda — decisión explícita del usuario (2026-10-15): el objetivo es
 * que una tienda nueva se conecte al backend compartido sin tocar CORS ni dominios, y que el
 * valor real nunca viaje hasta el navegador (lo inyecta, server-side, el nginx propio de cada
 * tienda — ver notas de despliegue). Solo se guarda el HASH (SHA-256), igual que una contraseña:
 * ni el superadmin puede volver a ver el valor real después de generarlo.
 *
 * Es ADITIVO a propósito: mientras una tienda no tenga key generada, sigue resolviéndose por
 * dominio/X-Tenant-Id exactamente como hoy (ver TenantInterceptor) — nada de esto afecta a
 * Calzacaribe/Duo Chic/Jey Avila hasta que alguien genere su key y el nginx de esa tienda
 * empiece a mandarla.
 */
@Component
@RequiredArgsConstructor
public class TiendaApiKeyService {

    // Igual TTL/razonamiento que TenantEstadoCache: tabla pequeña, consultarla en cada solicitud
    // de la plataforma sí suma. clear() completo en cada rotación (ver generar()) es más simple
    // que invalidar una sola entrada y el costo es insignificante.
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIJO = "sk_tienda_";

    private final TiendaRepository tiendaRepo;
    private final ConcurrentHashMap<String, Entrada> cache = new ConcurrentHashMap<>();

    private record Entrada(Long tndId, Instant expiraEn) {}

    /** Genera una api key NUEVA (sustituye cualquier anterior) y devuelve el valor en texto
     *  plano — la ÚNICA vez que existe así en todo el sistema. Si se pierde, hay que generar
     *  otra; no hay forma de "recuperarla". */
    @Transactional
    public String generar(Long tndId) {
        Tienda tienda = tiendaRepo.findById(tndId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = PREFIJO + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tienda.setApiKeyHash(sha256(raw));
        tiendaRepo.save(tienda);
        cache.clear();
        return raw;
    }

    /** null si la key no existe, no corresponde a ninguna tienda, o esa tienda está inactiva. */
    public Long resolverTenantId(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        String hash = sha256(rawKey);
        Entrada actual = cache.get(hash);
        if (actual != null && Instant.now().isBefore(actual.expiraEn())) {
            return actual.tndId();
        }
        Long tndId = tiendaRepo.findByApiKeyHashAndActivoTrue(hash).map(Tienda::getId).orElse(null);
        if (tndId != null) {
            cache.put(hash, new Entrada(tndId, Instant.now().plus(TTL)));
        }
        return tndId;
    }

    private String sha256(String valor) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(valor.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 siempre existe en cualquier JVM estándar
        }
    }
}
