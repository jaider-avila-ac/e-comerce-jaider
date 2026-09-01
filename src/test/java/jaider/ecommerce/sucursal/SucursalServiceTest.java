package jaider.ecommerce.sucursal;

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
 * Integración real (BD local, sin mocks) — PLAN_INTEGRACION_ENVIA.md: editar una sucursal ya
 * existente (dirección de origen, contacto) desde el panel de admin normal. Crear una sucursal
 * nueva sigue siendo exclusivo del superadmin (decisión explícita del usuario, 2026-09-01) —
 * a propósito no hay create acá, solo update.
 *
 * @Transactional: revierte todo solo al terminar cada test.
 */
@SpringBootTest
@Transactional
class SucursalServiceTest {

    @Autowired
    private SucursalService service;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void actualizar_soloTocaLosCamposQueVienen() {
        TenantContext.set("58"); // Ampaz Studio — ya tiene una sucursal "Principal"
        Long sucursalId = service.listar().stream()
                .filter(s -> "Principal".equals(s.nombre()))
                .findFirst().orElseThrow().id();

        var actualizada = service.actualizar(sucursalId, new SucursalUpdateRequest(
                null, null, null, null, null, null, null, null, null, "470099"));

        assertThat(actualizada.envioOrigenCodigoPostal()).isEqualTo("470099");
        assertThat(actualizada.envioOrigenMunicipio()).isEqualTo("Santa Marta"); // no se tocó

        // Deja el código postal como estaba, para no afectar la BD local fuera de este test.
        service.actualizar(sucursalId, new SucursalUpdateRequest(
                null, null, null, null, null, null, null, null, null, "470001"));
    }

    @Test
    void actualizar_sucursalInexistente_da404() {
        TenantContext.set("58");
        assertThatThrownBy(() -> service.actualizar(999999L, new SucursalUpdateRequest(
                null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no encontrada");
    }

    @Test
    void actualizar_deOtraTienda_noSeVe() {
        TenantContext.set("1"); // Calzacaribe
        Long sucursalDeAmpaz = 999999L; // id inventado a propósito — RLS igual lo bloquearía
        // aunque existiera de verdad, porque pertenece a otro tenant.
        assertThatThrownBy(() -> service.actualizar(sucursalDeAmpaz, new SucursalUpdateRequest(
                null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
