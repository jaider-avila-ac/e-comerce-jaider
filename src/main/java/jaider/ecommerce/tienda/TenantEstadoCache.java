package jaider.ecommerce.tienda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caché corto de "¿este tenant existe y está activo?" (§3.3 del plan: "tenant inexistente o
 * inactivo: rechazar la operación"). Lo usa únicamente {@link TenantInterceptor}, una vez por
 * solicitud — es el único punto donde convergen las 3 fuentes de tenant (JWT, dominio, header),
 * así que basta un solo chequeo ahí en vez de repetirlo en cada llamada a
 * {@code TenantSupport.requireTenant()} dentro de la misma transacción.
 *
 * TTL corto (no un chequeo síncrono en cada solicitud) a propósito: `tiendas` es una tabla
 * pequeña y el chequeo es barato, pero consultarla en CADA solicitud de la plataforma completa
 * sí suma. El TTL de 60s es la misma decisión de trade-off documentada en
 * TenantCloudinaryClients (10 min ahí, más corto acá porque desactivar una tienda debe notarse
 * casi de inmediato — es una acción de seguridad/negocio, no una rotación de credenciales).
 */
@Component
@RequiredArgsConstructor
public class TenantEstadoCache {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final TiendaRepository tiendaRepository;
    private final ConcurrentHashMap<Long, Entrada> cache = new ConcurrentHashMap<>();

    private record Entrada(boolean existeYActivo, Instant expiraEn) {}

    public boolean existeYActivo(Long tndId) {
        Entrada actual = cache.get(tndId);
        if (actual != null && Instant.now().isBefore(actual.expiraEn())) {
            return actual.existeYActivo();
        }
        boolean resultado = tiendaRepository.existsByIdAndActivoTrue(tndId);
        cache.put(tndId, new Entrada(resultado, Instant.now().plus(TTL)));
        return resultado;
    }
}
