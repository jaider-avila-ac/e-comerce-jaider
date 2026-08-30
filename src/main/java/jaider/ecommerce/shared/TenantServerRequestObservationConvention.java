package jaider.ecommerce.shared;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jaider.ecommerce.shared.interceptor.TenantInterceptor;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Agrega la etiqueta "tenant" a la métrica automática http.server.requests que Spring Boot ya
 * genera para cada solicitud (Actuator + Micrometer, sin dependencia nueva) — cubre "duración de
 * consultas" por tenant (§14) sin necesidad de un contador/timer manual: Spring Boot detecta
 * este bean y lo usa en vez de la convención por defecto (WebMvcObservationAutoConfiguration).
 *
 * Lee el tenant de un ATRIBUTO del HttpServletRequest (ver TenantInterceptor.REQUEST_ATTR_TENANT),
 * no de TenantContext (ThreadLocal) — Micrometer cierra/etiqueta la Observation DESPUÉS de que
 * TenantInterceptor.afterCompletion() ya limpió ese ThreadLocal, así que leerlo acá siempre daría
 * "desconocido". El atributo del request, en cambio, vive mientras dure el objeto request.
 */
@Component
public class TenantServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        Object tenant = context.getCarrier().getAttribute(TenantInterceptor.REQUEST_ATTR_TENANT);
        return super.getLowCardinalityKeyValues(context)
                .and(KeyValue.of("tenant", tenant != null ? tenant.toString() : "desconocido"));
    }
}
