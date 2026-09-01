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
 * Integración real (BD local, sin mocks) del catálogo de especificaciones logísticas —
 * PLAN_INTEGRACION_ENVIA.md, Fase 1. Cubre aislamiento entre tenants (RLS forzado) y
 * validaciones básicas.
 *
 * @Transactional: todo se revierte solo al terminar cada test.
 */
@SpringBootTest
@Transactional
class EspecificacionLogisticaServiceTest {

    @Autowired
    private EspecificacionLogisticaService service;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void tenant1NuncaVeLasEspecificacionesDeTenant2_yViceversa() {
        TenantContext.set("2");
        var fixtureTenant2 = service.create(new EspecificacionLogisticaRequest(
                "Fixture T2 " + System.nanoTime(), 500, (short) 20, (short) 15, (short) 10, true));

        TenantContext.set("1");
        assertThat(service.getAll()).noneMatch(e -> e.id().equals(fixtureTenant2.id()));

        TenantContext.set("2");
        assertThat(service.getAll()).anyMatch(e -> e.id().equals(fixtureTenant2.id()));
    }

    @Test
    void pesoOMedidasInvalidas_dan400() {
        TenantContext.set("1");
        assertThatThrownBy(() -> service.create(new EspecificacionLogisticaRequest(
                "Inválida " + System.nanoTime(), 0, (short) 20, (short) 15, (short) 10, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("peso debe ser mayor a 0");
    }

    @Test
    void nombreDuplicadoEnLaMismaTienda_daConflicto() {
        TenantContext.set("1");
        String nombre = "Duplicada " + System.nanoTime();
        service.create(new EspecificacionLogisticaRequest(nombre, 500, (short) 20, (short) 15, (short) 10, true));

        assertThatThrownBy(() -> service.create(new EspecificacionLogisticaRequest(
                nombre, 600, (short) 25, (short) 18, (short) 12, true)))
                .isInstanceOf(Exception.class); // el UNIQUE(tnd_id, nombre) de la BD lo rechaza
    }
}
