package jaider.ecommerce.shared.interceptor;

import jaider.ecommerce.tienda.TenantDomainResolver;
import jaider.ecommerce.tienda.TenantEstadoCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Extrae el tenant del request y lo guarda en ThreadLocal.
 * El TenantSupport lo usa para inyectar SET LOCAL app.current_tnd_id en cada transacción.
 *
 * Este interceptor corre DESPUÉS de {@link jaider.ecommerce.auth.jwt.JwtAuthFilter} (los
 * Filter de servlet siempre se ejecutan antes que los HandlerInterceptor de Spring MVC), así
 * que si ya hay tenant en el contexto al llegar acá, viene de un JWT válido y firmado — es la
 * fuente autorizada. El header X-Tenant-Id NUNCA puede pisar silenciosamente ese valor: solo
 * se usa como fuente del tenant en rutas públicas (sin JWT), y si llega junto con un JWT debe
 * coincidir exactamente o la solicitud se rechaza con 403. Esto es lo que exige la sección 3.2
 * de PLAN_MEJORAS_API_ECOMMERCE_MULTITENANT.md: un cliente autenticado en la tienda A no puede
 * leer/escribir datos de la tienda B solo cambiando este header.
 *
 * En rutas públicas (sin JWT), el dominio (§5 del plan) tiene prioridad sobre X-Tenant-Id — un
 * navegador no puede falsificar en qué dominio real está parado, mientras que el header sí es
 * un valor arbitrario que el propio cliente decide mandar. X-Tenant-Id sigue funcionando como
 * respaldo cuando el Host no coincide con ningún dominio registrado (dev local, Postman, etc.).
 *
 * También valida, una sola vez por solicitud sin importar la fuente (JWT, dominio o header),
 * que el tenant resuelto exista, esté activo y esté bien formado (§3.3 del plan) — ver
 * {@link #validarTenantExisteYActivo} y {@link TenantEstadoCache}.
 */
@Component
@RequiredArgsConstructor
public class TenantInterceptor implements HandlerInterceptor {

    private final TenantDomainResolver domainResolver;
    private final TenantEstadoCache estadoCache;

    /** Nombre del atributo del request donde queda el tenant ya resuelto — a diferencia de
     *  TenantContext (ThreadLocal, se limpia en afterCompletion), un atributo del request vive
     *  mientras dure el objeto HttpServletRequest, así que sigue disponible cuando Micrometer
     *  cierra la Observation de la solicitud (después de afterCompletion). Ver
     *  TenantServerRequestObservationConvention, que lo usa para etiquetar http.server.requests
     *  por tenant sin depender de un timing de ThreadLocal. */
    public static final String REQUEST_ATTR_TENANT = "jaider.ecommerce.tenant";

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String headerTenantId = request.getHeader("X-Tenant-Id");
        String jwtTenantId = TenantContext.get();

        if (jwtTenantId != null) {
            if (headerTenantId != null && !headerTenantId.isBlank()
                    && !headerTenantId.trim().equals(jwtTenantId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "El tenant del token no coincide con X-Tenant-Id");
            }
            validarTenantExisteYActivo(jwtTenantId);
            request.setAttribute(REQUEST_ATTR_TENANT, jwtTenantId);
            return true;
        }

        Optional<Long> tndIdPorDominio = domainResolver.resolveTenantId(hostReal(request));
        if (tndIdPorDominio.isPresent()) {
            TenantContext.set(tndIdPorDominio.get().toString());
        } else if (headerTenantId != null && !headerTenantId.isBlank()) {
            TenantContext.set(headerTenantId.trim());
        }
        if (TenantContext.get() != null) {
            validarTenantExisteYActivo(TenantContext.get());
            request.setAttribute(REQUEST_ATTR_TENANT, TenantContext.get());
        }
        return true;
    }

    /**
     * §3.3 del plan: "tenant inexistente o inactivo: rechazar la operación" / "tenant mal
     * formado: responder error de validación". Se hace UNA sola vez acá, sin importar de cuál de
     * las 3 fuentes vino el tenant (JWT, dominio o header) — incluye al JWT a propósito: un token
     * ya emitido para una tienda que se desactiva después de emitido debe dejar de funcionar sin
     * esperar a que expire (con el TTL del caché, en un máximo de 60s).
     */
    private void validarTenantExisteYActivo(String tndIdStr) {
        long tndId;
        try {
            tndId = Long.parseLong(tndIdStr);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant mal formado");
        }
        if (!estadoCache.existeYActivo(tndId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "La tienda solicitada no existe o no está activa");
        }
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        TenantContext.clear();
    }

    /** Igual patrón que UsuarioAuthController.clientIp(): detrás del proxy de Coolify, el Host
     *  de conexión real siempre es el del proxy — hay que leer X-Forwarded-Host cuando está
     *  presente (puede traer varios separados por coma si hay más de un proxy). */
    private String hostReal(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-Host");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getHeader("Host");
    }
}
