package jaider.ecommerce.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test puro para el estado del circuit breaker (§14) — sin red, sin Spring. */
class TenantCircuitBreakerTest {

    private final TenantCircuitBreaker cb = new TenantCircuitBreaker();

    @Test
    void empiezaCerrado() {
        assertThat(cb.abierto(1L, "resend")).isFalse();
    }

    @Test
    void seAbreTrasCincoFallosSeguidos() {
        for (int i = 0; i < 4; i++) cb.registrarFallo(1L, "resend");
        assertThat(cb.abierto(1L, "resend")).isFalse(); // todavía no llega al umbral

        cb.registrarFallo(1L, "resend"); // 5to fallo seguido
        assertThat(cb.abierto(1L, "resend")).isTrue();
    }

    @Test
    void unExitoReiniciaElContadorDeFallos() {
        for (int i = 0; i < 4; i++) cb.registrarFallo(1L, "resend");
        cb.registrarExito(1L, "resend");

        cb.registrarFallo(1L, "resend"); // si no se hubiera reiniciado, este sería el 5to
        assertThat(cb.abierto(1L, "resend")).isFalse();
    }

    @Test
    void elEstadoEsIndependientePorTenant() {
        for (int i = 0; i < 5; i++) cb.registrarFallo(1L, "resend");
        assertThat(cb.abierto(1L, "resend")).isTrue();
        assertThat(cb.abierto(2L, "resend")).isFalse(); // otra tienda, no le afecta
    }

    @Test
    void elEstadoEsIndependientePorProveedor() {
        for (int i = 0; i < 5; i++) cb.registrarFallo(1L, "resend");
        assertThat(cb.abierto(1L, "resend")).isTrue();
        assertThat(cb.abierto(1L, "cloudinary")).isFalse(); // mismo tenant, otro proveedor
    }
}
