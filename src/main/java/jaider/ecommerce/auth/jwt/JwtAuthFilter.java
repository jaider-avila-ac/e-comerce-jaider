package jaider.ecommerce.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // Todo el cuerpo va en try/finally con TenantContext.clear() incondicional en el finally.
        // Este filtro es un OncePerRequestFilter: Spring Security lo ejecuta para TODA solicitud
        // que llegue, ANTES del AuthorizationFilter que aplica los .hasRole(...) de SecurityConfig.
        // Si ese filtro de autorización rechaza la solicitud (403), la excepción se lanza dentro
        // de la cadena de filtros — nunca llega al DispatcherServlet, así que TenantInterceptor
        // (cuyo afterCompletion es la única otra limpieza que existía) JAMÁS se ejecuta. Sin este
        // finally, el ThreadLocal quedaba fijado en el hilo del pool de Tomcat, y como esos hilos
        // se reutilizan, una solicitud pública SIN Authorization posterior en ese mismo hilo
        // heredaba el tenant de la solicitud anterior (TenantInterceptor lo habría tomado como si
        // viniera de un JWT válido). Con el finally, este filtro deja el hilo limpio sin importar
        // qué pase después en la cadena (autorización rechazada, excepción del controller, éxito).
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                filterChain.doFilter(request, response);
                return;
            }

            String token = header.substring(7);
            if (!jwtService.isValid(token)) {
                filterChain.doFilter(request, response);
                return;
            }

            // Inyecta el tenant desde el JWT para que el RLS de PostgreSQL funcione
            Long tndId = jwtService.extractTndId(token);
            if (tndId != null) {
                TenantContext.set(tndId.toString());
            }

            String email = jwtService.extractEmail(token);
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    UserDetails user = userDetailsService.loadUserByUsername(email);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (UsernameNotFoundException ignored) {
                    // Token válido pero no es usuario admin (puede ser usuario tienda)
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
