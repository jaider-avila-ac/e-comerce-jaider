package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.pedido.Pedido;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.integracion.EnviaCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnvioGuiaServiceTest {

    @Mock EnvioGuiaTransaccionesService transacciones;
    @Mock EnvioCotizacionService cotizacionService;
    @Mock EnviaGeocodesClient geocodesClient;
    @Mock EnviaRateClient rateClient;
    @Mock EnviaLabelClient labelClient;

    private EnvioGuiaService service;
    private Pedido pedido;

    @BeforeEach
    void setUp() {
        service = new EnvioGuiaService(transacciones, cotizacionService, geocodesClient, rateClient, labelClient);
        pedido = new Pedido();
        pedido.setId(55L);
        pedido.setTndId(7L);
        pedido.setNumero("PED-55");
        DireccionEnvia direccion = new DireccionEnvia("Nombre", "3000000000", "Calle 1", "Bogotá", "Bogotá", "110111");
        Tienda tienda = new Tienda();
        tienda.setEnviaAmbiente("sandbox");
        var datos = new EnvioGuiaTransaccionesService.DatosGuia(pedido, tienda, List.of(), direccion, direccion,
                new EnviaCredentials("token", "webhook"), "https://api-test.envia.com", 100_000L);
        when(transacciones.cargarDatosParaGuia(7L, 55L)).thenReturn(datos);
        when(geocodesClient.resolver("110111")).thenReturn(new GeocodeResultado("Bogotá", "DC", "11001000"));
        when(transacciones.reservar(55L)).thenReturn(1);
    }

    @Test
    void timeoutMarcaResultadoInciertoYNoLiberaReserva() {
        when(labelClient.generar(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.generarGuia(7L, 55L,
                new GenerarGuiaRequest("servientrega", "express"), 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");

        verify(transacciones).marcarResultadoIncierto(55L);
        verify(transacciones, never()).liberarReserva(55L);
    }

    @Test
    void rechazoExplicitoLiberaReserva() {
        when(labelClient.generar(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new EnviaGuiaRechazadaException("rechazada"));

        assertThatThrownBy(() -> service.generarGuia(7L, 55L,
                new GenerarGuiaRequest("servientrega", "express"), 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502");

        verify(transacciones).liberarReserva(55L);
        verify(transacciones, never()).marcarResultadoIncierto(55L);
    }
}
