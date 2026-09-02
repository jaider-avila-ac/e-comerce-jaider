package jaider.ecommerce.tienda.envio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CotizacionTokenServiceTest {

    private final CotizacionTokenService service = new CotizacionTokenService(
            "secreto-de-pruebas-suficientemente-largo-para-hmac-256");

    @Test
    void tokenSoloSirveParaMismoTenantDireccionYCarrito() {
        List<PaqueteCalculado> paquetes = List.of(
                new PaqueteCalculado(2L, "Grande", 1, 2000, (short) 40, (short) 30, (short) 20),
                new PaqueteCalculado(1L, "Pequeño", 2, 900, (short) 20, (short) 15, (short) 10));
        String destino = service.claveDestino("Ana", "300", "Calle 1", "Bogotá", "Bogotá", "110111");
        String huella = service.huellaCotizacion(paquetes, 250_000L, destino);
        var cotizacion = new CotizacionTokenService.CotizacionFirmada(
                "servientrega", "express", "Express", "1-2 días", 25_000L, false);
        String token = service.firmar(10L, 20L, 30L, huella, cotizacion);

        assertThat(service.verificar(token, 10L, 20L, 30L, huella)).contains(cotizacion);
        assertThat(service.verificar(token, 10L, 20L, 30L,
                service.huellaCotizacion(paquetes, 251_000L, destino))).isEmpty();
        assertThat(service.verificar(token, 10L, 20L, 30L,
                service.huellaCotizacion(paquetes, 250_000L,
                        service.claveDestino("Ana", "300", "Calle 1", "Medellín", "Antioquia", "050001")))).isEmpty();
        assertThat(service.verificar(token, 10L, 99L, 30L, huella)).isEmpty();
    }

    @Test
    void huellaEsDeterministicaAunqueCambieOrdenDePaquetes() {
        PaqueteCalculado a = new PaqueteCalculado(1L, "A", 1, 1000, (short) 10, (short) 10, (short) 10);
        PaqueteCalculado b = new PaqueteCalculado(2L, "B", 2, 2000, (short) 20, (short) 20, (short) 20);

        assertThat(service.huellaCotizacion(List.of(a, b), 100_000L, "destino"))
                .isEqualTo(service.huellaCotizacion(List.of(b, a), 100_000L, "destino"));
    }
}
