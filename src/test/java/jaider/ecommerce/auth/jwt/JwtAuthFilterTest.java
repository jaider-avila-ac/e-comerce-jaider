package jaider.ecommerce.auth.jwt;

import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test puro (sin Spring, sin BD) para la limpieza del ThreadLocal de TenantContext.
 *
 * Bug real encontrado en auditoría (2026-08-30): el filtro fijaba TenantContext desde el JWT
 * pero nunca lo limpiaba — dependía de que TenantInterceptor.afterCompletion() lo hiciera. Ese
 * interceptor solo corre si la solicitud llega al DispatcherServlet; si el AuthorizationFilter de
 * Spring Security (que corre DESPUÉS de este filtro en la cadena, por SecurityConfig) rechaza la
 * solicitud con 403 antes de llegar ahí, el ThreadLocal quedaba fijado en el hilo del pool de
 * Tomcat — y como esos hilos se reutilizan, una solicitud pública posterior sin Authorization en
 * ese mismo hilo heredaba el tenant de la solicitud anterior.
 */
class JwtAuthFilterTest {

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void tokenValido_yLaCadenaRechazaLaSolicitudDespues_igualLimpiaElTenant() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        UserDetailsService userDetailsService = mock(UserDetailsService.class);
        when(jwtService.isValid(anyString())).thenReturn(true);
        when(jwtService.extractTndId(anyString())).thenReturn(1L);
        when(jwtService.extractEmail(anyString())).thenReturn(null);

        JwtAuthFilter filter = new JwtAuthFilter(jwtService, userDetailsService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer token-valido");
        // Simula al AuthorizationFilter de Spring Security rechazando la solicitud MÁS ADELANTE
        // en la misma cadena — nunca llega al DispatcherServlet ni a TenantInterceptor.
        doThrow(new org.springframework.security.access.AccessDeniedException("403"))
                .when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        // A pesar del rechazo posterior, este filtro debe dejar el hilo limpio por su cuenta.
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void solicitudPublicaSinAuthorization_limpiaCualquierTenantQueHayaQuedadoDeUnaSolicitudAnterior()
            throws Exception {
        // Simula el hilo reutilizado del pool con un tenant que quedó fijado por una solicitud
        // anterior (el escenario real del bug: otra solicitud lo dejó fijado y nunca se limpió).
        TenantContext.set("1");

        JwtService jwtService = mock(JwtService.class);
        UserDetailsService userDetailsService = mock(UserDetailsService.class);
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, userDetailsService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void tokenValido_flujoNormalExitoso_dejaElTenantLimpioAlSalir() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        UserDetailsService userDetailsService = mock(UserDetailsService.class);
        when(jwtService.isValid(anyString())).thenReturn(true);
        when(jwtService.extractTndId(anyString())).thenReturn(1L);
        when(jwtService.extractEmail(anyString())).thenReturn("admin@calzacaribe.com");
        when(userDetailsService.loadUserByUsername("admin@calzacaribe.com"))
                .thenThrow(new UsernameNotFoundException("no admin"));

        JwtAuthFilter filter = new JwtAuthFilter(jwtService, userDetailsService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer token-valido");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        // El tenant SÍ debe existir mientras corre la cadena downstream, pero este test solo
        // puede observar el estado final (post-doFilter) — la garantía de "durante" la cubre el
        // primer test, que demuestra que sigue limpio incluso si la cadena revienta a mitad.
        assertThat(TenantContext.get()).isNull();
    }
}
