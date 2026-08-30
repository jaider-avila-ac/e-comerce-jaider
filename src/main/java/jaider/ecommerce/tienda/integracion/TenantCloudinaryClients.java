package jaider.ecommerce.tienda.integracion;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caché corto de clientes Cloudinary, uno por tenant (§6.3: "mantener un caché corto y seguro,
 * sin registrar los valores... permitir rotación o recarga controlada").
 *
 * Cada tienda tiene su propia cuenta Cloudinary — nunca se comparte ni se muta un único cliente
 * singleton entre tenants (§9.1). Construir un Cloudinary es barato (no hace red, solo guarda
 * config), así que el único motivo del caché es evitar reconstruirlo en cada upload; el TTL
 * corto asegura que una rotación de llaves (nueva CLOUDINARY_<ALIAS>_API_SECRET) se recoja sin
 * tener que reiniciar el backend.
 */
@Component
@RequiredArgsConstructor
public class TenantCloudinaryClients {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final TenantIntegrationResolver integrationResolver;
    private final ConcurrentHashMap<Long, Entrada> cache = new ConcurrentHashMap<>();

    private record Entrada(Cloudinary cliente, Instant expiraEn) {}

    public Cloudinary get(Long tndId) {
        Entrada actual = cache.get(tndId);
        if (actual != null && Instant.now().isBefore(actual.expiraEn())) {
            return actual.cliente();
        }
        Cloudinary nuevo = construir(tndId);
        cache.put(tndId, new Entrada(nuevo, Instant.now().plus(TTL)));
        return nuevo;
    }

    private Cloudinary construir(Long tndId) {
        CloudinaryCredentials creds = integrationResolver.mediaCredentials(tndId);
        return new Cloudinary(ObjectUtils.asMap(
                "cloud_name", creds.cloudName(),
                "api_key", creds.apiKey(),
                "api_secret", creds.apiSecret(),
                "secure", true
        ));
    }
}
