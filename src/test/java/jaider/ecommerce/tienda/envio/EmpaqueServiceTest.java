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
 * Integración real (BD local, sin mocks) del catálogo de empaques — PLAN_INTEGRACION_ENVIA.md,
 * Fase 1. Cubre aislamiento entre tenants (RLS forzado en tienda_empaques) y las validaciones de
 * negocio (dimensiones positivas, rango de cantidad coherente, sin solape entre empaques).
 *
 * @Transactional: todo se revierte solo al terminar cada test.
 */
@SpringBootTest
@Transactional
class EmpaqueServiceTest {

    @Autowired
    private EmpaqueService service;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void tenant1NuncaVeLosEmpaquesDeTenant2_yViceversa() {
        TenantContext.set("2");
        var empaqueTenant2 = service.create(new EmpaqueRequest(
                "Fixture T2 " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, 1, null, (short) 0, true));

        TenantContext.set("1");
        var listaTenant1 = service.getAll();
        assertThat(listaTenant1).noneMatch(e -> e.id().equals(empaqueTenant2.id()));

        TenantContext.set("2");
        var listaTenant2 = service.getAll();
        assertThat(listaTenant2).anyMatch(e -> e.id().equals(empaqueTenant2.id()));
    }

    @Test
    void dimensionesInvalidas_dan400_noSeGuardaNada() {
        TenantContext.set("1");
        assertThatThrownBy(() -> service.create(new EmpaqueRequest(
                "Inválido " + System.nanoTime(), (short) 0, (short) 20, (short) 12, 150, 1, null, (short) 0, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("mayores a 0");
    }

    @Test
    void cantidadMaxMenorQueMin_da400() {
        TenantContext.set("1");
        assertThatThrownBy(() -> service.create(new EmpaqueRequest(
                "Rango invertido " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, 5, 2, (short) 0, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no puede ser menor");
    }

    @Test
    void rangosQueSeSolapan_dan409_noSeGuardaElSegundo() {
        TenantContext.set("1");
        service.create(new EmpaqueRequest(
                "Base " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, 1, 3, (short) 0, true));

        assertThatThrownBy(() -> service.create(new EmpaqueRequest(
                "Solapado " + System.nanoTime(), (short) 40, (short) 30, (short) 18, 250, 2, 5, (short) 1, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("se solapa");
    }
}
