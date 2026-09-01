package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.shared.interceptor.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real (BD local, sin mocks) del orden de transportadoras por tienda —
 * PLAN_INTEGRACION_ENVIA.md, Fase 3. Pedido explícito del usuario: el orden en el que se
 * intenta cotizar (Servientrega primero, etc.) lo decide el administrador, no queda fijo en el
 * código — ver {@link EnvioCotizacionService#ordenTransportadoras}.
 *
 * @Transactional: todo se revierte solo al terminar cada test.
 */
@SpringBootTest
@Transactional
class TransportadoraServiceTest {

    @Autowired
    private TransportadoraService service;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void tenant1NuncaVeLasTransportadorasDeTenant2_yViceversa() {
        TenantContext.set("2");
        var t2 = service.create(new TransportadoraRequest("coordinadora", (short) 0, true));

        TenantContext.set("1");
        assertThat(service.getAll()).noneMatch(t -> t.id().equals(t2.id()));

        TenantContext.set("2");
        assertThat(service.getAll()).anyMatch(t -> t.id().equals(t2.id()));
    }

    @Test
    void carrierInvalido_da400_conMensajeClaro() {
        TenantContext.set("1");
        assertThatThrownBy(() -> service.create(new TransportadoraRequest("dhl", (short) 0, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Transportadora inválida");
    }

    @Test
    void carrierDuplicadoParaLaMismaTienda_da409() {
        TenantContext.set("1");
        var creada = service.create(new TransportadoraRequest("interrapidisimo", (short) 5, true));
        try {
            assertThatThrownBy(() -> service.create(new TransportadoraRequest("interrapidisimo", (short) 1, true)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("ya tiene configurada");
        } finally {
            service.delete(creada.id());
        }
    }

    @Test
    void crearActualizarYBorrar_funcionaCompleto() {
        TenantContext.set("1");
        var creada = service.create(new TransportadoraRequest("servientrega", (short) 0, true));
        assertThat(creada.carrier()).isEqualTo("servientrega");

        var actualizada = service.update(creada.id(), new TransportadoraRequest(null, (short) 9, false));
        assertThat(actualizada.orden()).isEqualTo((short) 9);
        assertThat(actualizada.activo()).isFalse();

        service.delete(creada.id());
        assertThat(service.getAll()).noneMatch(t -> t.id().equals(creada.id()));
    }
}
