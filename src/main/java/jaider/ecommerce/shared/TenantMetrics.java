package jaider.ecommerce.shared;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Contadores (por tenant cuando aplica) para las fallas que el plan pide observar (§14):
 * "correos fallidos, uploads fallidos, webhooks rechazados, errores de pago, jobs fallidos,
 * rate limits alcanzados" — quedan disponibles en /actuator/metrics (Actuator + Micrometer ya
 * venían en el proyecto, sin dependencia nueva). La duración de consultas HTTP por tenant se
 * cubre aparte, vía TenantWebMvcTagsContributor sobre la métrica automática http.server.requests
 * de Spring — no hace falta un contador manual para eso.
 */
@Component
@RequiredArgsConstructor
public class TenantMetrics {

    private final MeterRegistry registry;

    public void emailFallido(Long tndId) {
        registry.counter("ecommerce.email.fallido", "tenant", tag(tndId)).increment();
    }

    public void uploadFallido(Long tndId) {
        registry.counter("ecommerce.upload.fallido", "tenant", tag(tndId)).increment();
    }

    public void webhookRechazado(Long tndId, String motivo) {
        registry.counter("ecommerce.webhook.rechazado", "tenant", tag(tndId), "motivo", motivo).increment();
    }

    public void pagoError(Long tndId, String proveedor, String operacion) {
        registry.counter("ecommerce.pago.error", "tenant", tag(tndId), "proveedor", proveedor, "operacion", operacion).increment();
    }

    public void jobFallido(String job) {
        registry.counter("ecommerce.job.fallido", "job", job).increment();
    }

    /** No siempre hay tenant disponible (ej. rate limit de login, que corre ANTES de resolver
     *  cualquier tenant) — "contexto" identifica qué límite se alcanzó. */
    public void rateLimitAlcanzado(String contexto) {
        registry.counter("ecommerce.rate_limit.alcanzado", "contexto", contexto).increment();
    }

    private String tag(Long tndId) {
        return tndId != null ? tndId.toString() : "desconocido";
    }
}
