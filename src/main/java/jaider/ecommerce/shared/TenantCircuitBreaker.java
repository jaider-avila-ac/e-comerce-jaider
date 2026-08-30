package jaider.ecommerce.shared;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Circuit breaker en memoria, por (tenant, proveedor) — §14: "aplicar circuit breakers
 * separados por tenant/integración cuando sea posible... una cuenta Wompi/Resend/Cloudinary mal
 * configurada no debe inutilizar a las demás". Sin dependencia nueva (no resilience4j): el
 * estado de una tienda con una integración caída/mal configurada nunca afecta el de otra, porque
 * la clave siempre incluye el tenant.
 *
 * Tras {@link #UMBRAL_FALLOS} fallos SEGUIDOS para el mismo (tenant, proveedor), el circuito
 * queda "abierto" por {@link #TIEMPO_ABIERTO}: durante ese tiempo, ni se intenta la llamada real
 * (evita seguir esperando timeouts de un proveedor que ya se sabe que está caído para esa
 * tienda) — el llamador debe seguir comportándose igual que ante cualquier otro fallo (nunca
 * lanzar una excepción distinta solo porque el circuito esté abierto).
 *
 * Cableado hoy en Resend y Cloudinary (integraciones que ya atrapan su propio error y no
 * bloquean la operación de negocio). NO se cableó en Wompi/pagos en esta sesión — es el flujo
 * más sensible (checkout) y agregarlo con prisa al final de un cambio grande es más riesgo que
 * beneficio; queda anotado como pendiente real, no una vulnerabilidad, solo una mejora futura.
 */
@Component
public class TenantCircuitBreaker {

    private static final int UMBRAL_FALLOS = 5;
    private static final Duration TIEMPO_ABIERTO = Duration.ofMinutes(2);

    private record Estado(int fallosConsecutivos, Instant abiertoHasta) {}

    private final ConcurrentHashMap<String, Estado> estados = new ConcurrentHashMap<>();

    public boolean abierto(Long tndId, String proveedor) {
        Estado e = estados.get(clave(tndId, proveedor));
        return e != null && e.abiertoHasta() != null && Instant.now().isBefore(e.abiertoHasta());
    }

    public void registrarExito(Long tndId, String proveedor) {
        estados.remove(clave(tndId, proveedor));
    }

    public void registrarFallo(Long tndId, String proveedor) {
        estados.compute(clave(tndId, proveedor), (k, actual) -> {
            int fallos = (actual != null ? actual.fallosConsecutivos() : 0) + 1;
            Instant abiertoHasta = fallos >= UMBRAL_FALLOS ? Instant.now().plus(TIEMPO_ABIERTO) : null;
            return new Estado(fallos, abiertoHasta);
        });
    }

    private String clave(Long tndId, String proveedor) {
        return tndId + ":" + proveedor;
    }
}
