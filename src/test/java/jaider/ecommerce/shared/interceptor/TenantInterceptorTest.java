package jaider.ecommerce.shared.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test puro (sin contexto de Spring ni base de datos) para la regla de la sección 3.2
 * de PLAN_MEJORAS_API_ECOMMERCE_MULTITENANT.md: "El header X-Tenant-Id no puede ser la
 * autoridad en solicitudes autenticadas". Simula lo que ya dejó JwtAuthFilter en
 * TenantContext (si vino un JWT válido con tnd_id) antes de que corra este interceptor.
 *
 * Cubre exactamente los 3 escenarios verificados manualmente contra la BD local durante la
 * implementación (ver memoria de la sesión): sin header, header igual al del JWT, header
 * distinto al del JWT.
 */
class TenantInterceptorTest {

    private final TenantInterceptor interceptor = new TenantInterceptor();

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void sinJwtYSinHeader_noFijaTenant() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Tenant-Id")).thenReturn(null);

        boolean continua = interceptor.preHandle(request, response, new Object());

        assertThat(continua).isTrue();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void sinJwt_conHeader_usaElHeaderComoTenant() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Tenant-Id")).thenReturn("5");

        boolean continua = interceptor.preHandle(request, response, new Object());

        assertThat(continua).isTrue();
        assertThat(TenantContext.get()).isEqualTo("5");
    }

    @Test
    void conJwt_sinHeader_respetaElTenantDelJwt() {
        TenantContext.set("1"); // simula lo que JwtAuthFilter ya dejó desde un JWT válido
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Tenant-Id")).thenReturn(null);

        boolean continua = interceptor.preHandle(request, response, new Object());

        assertThat(continua).isTrue();
        assertThat(TenantContext.get()).isEqualTo("1");
    }

    @Test
    void conJwt_headerIgualAlDelJwt_pasaSinCambiarNada() {
        TenantContext.set("1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Tenant-Id")).thenReturn("1");

        boolean continua = interceptor.preHandle(request, response, new Object());

        assertThat(continua).isTrue();
        assertThat(TenantContext.get()).isEqualTo("1");
    }

    @Test
    void conJwtDeTenantA_headerDeTenantB_lanza403YNoPisaElTenant() {
        TenantContext.set("1"); // JWT firmado para la tienda A (tnd_id=1)
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Tenant-Id")).thenReturn("2"); // navegador pide la tienda B

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        // El tenant del JWT nunca debe quedar reemplazado por el valor del header, ni siquiera
        // en el momento en que se rechaza la solicitud.
        assertThat(TenantContext.get()).isEqualTo("1");
    }
}
