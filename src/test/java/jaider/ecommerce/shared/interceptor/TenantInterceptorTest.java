package jaider.ecommerce.shared.interceptor;

import jaider.ecommerce.tienda.TenantDomainResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test puro (sin contexto de Spring ni base de datos) para las dos reglas de resolución de
 * tenant en rutas del interceptor:
 *   - §3.2: "El header X-Tenant-Id no puede ser la autoridad en solicitudes autenticadas" —
 *     simula lo que ya dejó JwtAuthFilter en TenantContext (si vino un JWT válido con tnd_id).
 *   - §5: en rutas públicas (sin JWT), el dominio tiene prioridad sobre X-Tenant-Id.
 */
class TenantInterceptorTest {

    private TenantDomainResolver domainResolver;
    private TenantInterceptor interceptor;

    @BeforeEach
    void setUp() {
        domainResolver = mock(TenantDomainResolver.class);
        when(domainResolver.resolveTenantId(any())).thenReturn(Optional.empty());
        interceptor = new TenantInterceptor(domainResolver);
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void sinJwtYSinHeaderNiDominio_noFijaTenant() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Tenant-Id")).thenReturn(null);

        boolean continua = interceptor.preHandle(request, response, new Object());

        assertThat(continua).isTrue();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void sinJwtNiDominio_conHeader_usaElHeaderComoTenant() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Tenant-Id")).thenReturn("5");

        boolean continua = interceptor.preHandle(request, response, new Object());

        assertThat(continua).isTrue();
        assertThat(TenantContext.get()).isEqualTo("5");
    }

    @Test
    void sinJwt_dominioRegistrado_tienePrioridadSobreElHeader() {
        when(domainResolver.resolveTenantId(any())).thenReturn(Optional.of(1L));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Host")).thenReturn("calzacaribe.com");
        // El navegador intenta pedir la tienda 2 por header, pero el dominio real es de la 1 —
        // el dominio manda porque el cliente no puede falsificar en qué Host está parado.
        when(request.getHeader("X-Tenant-Id")).thenReturn("2");

        boolean continua = interceptor.preHandle(request, response, new Object());

        assertThat(continua).isTrue();
        assertThat(TenantContext.get()).isEqualTo("1");
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
