package jaider.ecommerce.shared.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Extrae el tenant del request y lo guarda en ThreadLocal.
 * El TenantContext lo usa el JPA interceptor para inyectar
 * SET LOCAL app.current_tnd_id en cada transacción.
 *
 * La tienda se identifica por el header X-Tenant-Id o por el slug
 * en el path (ej: /api/v1/{slug}/...). Se puede cambiar la estrategia
 * sin tocar el resto del código.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String tenantId = request.getHeader("X-Tenant-Id");
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.set(tenantId);
        }
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        TenantContext.clear();
    }
}
