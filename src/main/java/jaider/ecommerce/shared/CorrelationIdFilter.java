package jaider.ecommerce.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Pone un ID de correlación por solicitud en el MDC de SLF4J (§14: "Todos los logs de negocio
 * deben incluir... request_id/correlation_id") — así CUALQUIER log existente en la app (los que
 * ya imprimían tenant/usuario/operación a mano, y los que no) queda etiquetado con el mismo ID
 * sin tener que tocar cada línea de log uno por uno. Ver logging.pattern.level en
 * application.properties, que es lo que realmente lo imprime.
 *
 * Reusa X-Request-Id si el proxy/cliente ya mandó uno (para correlacionar con sus propios logs);
 * si no, genera uno nuevo. Se corre primero (@Order más bajo que el resto) para que estar
 * disponible desde el arranque mismo del request, incluida la autenticación JWT.
 */
@Component
@Order(Integer.MIN_VALUE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String MDC_KEY = "requestId";
    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && !incoming.isBlank())
                ? incoming.trim()
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
