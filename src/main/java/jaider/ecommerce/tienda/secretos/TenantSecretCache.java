package jaider.ecommerce.tienda.secretos;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caché corto de credenciales YA DESCIFRADAS por (tenant, proveedor, campo) — evita una consulta
 * a `tienda_secretos` + un descifrado en cada llamada a Wompi/Resend/Cloudinary (mismo patrón
 * que {@link jaider.ecommerce.tienda.integracion.TenantCloudinaryClients}).
 *
 * A diferencia de ese caché (TTL puro), acá {@link #invalidar} se llama explícitamente justo
 * después de guardar una credencial nueva desde el panel de superadmin — así el cambio se nota
 * de inmediato, sin esperar el TTL. El TTL (60s) queda solo como red de seguridad para el caso
 * en que alguien edite la tabla directo en la BD sin pasar por el servicio que invalida.
 */
@Component
@RequiredArgsConstructor
public class TenantSecretCache {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final TiendaSecretoRepository repo;
    private final SecretEncryptionService encryption;
    private final ConcurrentHashMap<String, Entrada> cache = new ConcurrentHashMap<>();

    private record Entrada(Optional<String> valor, Instant expiraEn) {}

    public Optional<String> get(Long tndId, String proveedor, String campo) {
        String key = clave(tndId, proveedor, campo);
        Entrada actual = cache.get(key);
        if (actual != null && Instant.now().isBefore(actual.expiraEn())) {
            return actual.valor();
        }
        Optional<String> valor = repo.findByTndIdAndProveedorAndCampo(tndId, proveedor, campo)
                .map(s -> encryption.decrypt(s.getValorCifrado()));
        cache.put(key, new Entrada(valor, Instant.now().plus(TTL)));
        return valor;
    }

    /** Llamar tras guardar/actualizar cualquier credencial de este (tenant, proveedor) — borra
     *  TODOS sus campos cacheados para que el próximo uso lea la BD de nuevo, ya. */
    public void invalidar(Long tndId, String proveedor) {
        String prefijo = tndId + ":" + proveedor + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefijo));
    }

    private String clave(Long tndId, String proveedor, String campo) {
        return tndId + ":" + proveedor + ":" + campo;
    }
}
