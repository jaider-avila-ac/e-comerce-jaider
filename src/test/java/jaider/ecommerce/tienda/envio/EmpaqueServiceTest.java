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
 * Fase 1. Un empaque junta peso + dimensiones (no hay tabla de peso aparte, ver diseño final en
 * TiendaEmpaque). Cubre aislamiento entre tenants (RLS forzado) y las validaciones de negocio
 * (dimensiones positivas, tope de 50cm por lado — límite real de Coordinadora).
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
                "Fixture T2 " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, (short) 0, true));

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
                "Inválido " + System.nanoTime(), (short) 0, (short) 20, (short) 12, 150, (short) 0, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("mayores a 0");
    }

    @Test
    void medidaMayorA50cm_dan400_limiteRealDeTransportadoras() {
        TenantContext.set("1");
        assertThatThrownBy(() -> service.create(new EmpaqueRequest(
                "Demasiado grande " + System.nanoTime(), (short) 60, (short) 20, (short) 12, 150, (short) 0, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("50cm");
    }

    @Test
    void pesoNulo_da400() {
        TenantContext.set("1");
        assertThatThrownBy(() -> service.create(new EmpaqueRequest(
                "Sin peso " + System.nanoTime(), (short) 30, (short) 20, (short) 12, null, (short) 0, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("peso");
    }
}
