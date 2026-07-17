package jaider.ecommerce.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Protección de fuerza bruta + anti-abuso para los login de admin y tienda, compartida entre
 * ambos — un identificador (típicamente "admin:{email}" o "tienda:{tndId}:{email}") agrupa dos
 * contadores independientes en Redis:
 *
 *  - Fallos consecutivos: pasados MAX_FALLOS en VENTANA_FALLOS, el identificador queda bloqueado
 *    por DURACION_BLOQUEO — ni siquiera la contraseña correcta lo destraba antes de tiempo.
 *  - Tasa general (éxitos y fallos por igual): tope de intentos por minuto y por 10 minutos, para
 *    que ni un login exitoso en bucle (ej. un script con credenciales válidas) pueda machacar el
 *    endpoint.
 *
 * Igual que TokenBlacklistService: si Redis falla, se falla abierto (no se bloquea a nadie) — un
 * problema de infraestructura de caché nunca debe tumbar el login de todo el negocio.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final int MAX_FALLOS = 5;
    private static final Duration VENTANA_FALLOS = Duration.ofMinutes(15);
    private static final Duration DURACION_BLOQUEO = Duration.ofMinutes(15);

    private static final int MAX_POR_MINUTO = 10;
    private static final int MAX_POR_10_MINUTOS = 30;

    private final StringRedisTemplate redis;

    /** Llamar al inicio de cada intento de login, antes de validar credenciales. */
    public void verificarLimite(String identificador) {
        try {
            if (redis.hasKey(bloqueoKey(identificador))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Demasiados intentos fallidos. Intenta de nuevo en unos minutos.");
            }
            long porMinuto = incrementarConTtl(tasaKey(identificador, "1m"), Duration.ofMinutes(1));
            long por10Minutos = incrementarConTtl(tasaKey(identificador, "10m"), Duration.ofMinutes(10));
            if (porMinuto > MAX_POR_MINUTO || por10Minutos > MAX_POR_10_MINUTOS) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Demasiados intentos. Espera un momento antes de volver a intentar.");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("No se pudo verificar el límite de login en Redis: {}", e.getMessage());
        }
    }

    /** Llamar cuando las credenciales resultan inválidas. */
    public void registrarFallo(String identificador) {
        try {
            Long fallos = redis.opsForValue().increment(fallosKey(identificador));
            if (fallos != null && fallos == 1L) {
                redis.expire(fallosKey(identificador), VENTANA_FALLOS);
            }
            if (fallos != null && fallos >= MAX_FALLOS) {
                redis.opsForValue().set(bloqueoKey(identificador), "1", DURACION_BLOQUEO);
            }
        } catch (Exception e) {
            log.warn("No se pudo registrar el fallo de login en Redis: {}", e.getMessage());
        }
    }

    /** Llamar cuando el login resulta exitoso, para limpiar el contador de fallos consecutivos. */
    public void registrarExito(String identificador) {
        try {
            redis.delete(fallosKey(identificador));
        } catch (Exception e) {
            log.warn("No se pudo limpiar el contador de fallos de login en Redis: {}", e.getMessage());
        }
    }

    private long incrementarConTtl(String key, Duration ttl) {
        Long valor = redis.opsForValue().increment(key);
        if (valor != null && valor == 1L) {
            redis.expire(key, ttl);
        }
        return valor == null ? 0L : valor;
    }

    private String fallosKey(String identificador) {
        return "login:fallos:" + identificador;
    }

    private String bloqueoKey(String identificador) {
        return "login:bloqueado:" + identificador;
    }

    private String tasaKey(String identificador, String ventana) {
        return "login:tasa:" + ventana + ":" + identificador;
    }
}
