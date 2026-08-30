package jaider.ecommerce.shared;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Contadores por tenant para las fallas de integración que el plan pide observar (§14):
 * "correos fallidos, uploads fallidos, webhooks rechazados" — quedan disponibles en
 * /actuator/metrics (Actuator + Micrometer ya venían en el proyecto, sin dependencia nueva).
 *
 * Pendiente (no cubierto todavía): errores de pago, duración de consultas, jobs fallidos, rate
 * limits alcanzados — se puede ampliar esta misma clase cuando se necesiten.
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

    private String tag(Long tndId) {
        return tndId != null ? tndId.toString() : "desconocido";
    }
}
