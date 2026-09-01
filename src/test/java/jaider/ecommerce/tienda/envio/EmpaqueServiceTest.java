package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.catalogo.producto.ProductoRequest;
import jaider.ecommerce.catalogo.producto.ProductoService;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

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

    @Autowired
    private ProductoService productoService;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

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

    // Corrección de auditoría (2026-09-01, tercera vuelta): desactivar/eliminar un empaque que un
    // producto ACTIVO sigue usando, en una tienda YA en modo 'envia' (Ampaz Studio, tenant 58),
    // rompía el checkout para el primer cliente que comprara ese producto — sin ninguna
    // revalidación después de la activación inicial.
    @Test
    void desactivarEmpaqueUsadoPorProductoActivo_bloqueaEnTiendaEnvia() {
        TenantContext.set("58");
        tenantSupport.requireTenant(em);
        var empaque = service.create(new EmpaqueRequest(
                "Fixture guard " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, (short) 0, true));
        Long catId = crearCategoriaFixture();
        productoService.create(new ProductoRequest(catId, null, "Producto fixture guard " + System.nanoTime(),
                "producto-fixture-guard-" + System.nanoTime(), null, 50000L, null, null, Map.of(), true,
                null, null, empaque.id()));

        assertThatThrownBy(() -> service.update(empaque.id(), new EmpaqueRequest(
                null, null, null, null, null, null, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("producto(s) activo(s)");
    }

    @Test
    void eliminarEmpaqueUsadoPorProductoActivo_bloqueaEnTiendaEnvia() {
        TenantContext.set("58");
        tenantSupport.requireTenant(em);
        var empaque = service.create(new EmpaqueRequest(
                "Fixture guard 2 " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, (short) 0, true));
        Long catId = crearCategoriaFixture();
        productoService.create(new ProductoRequest(catId, null, "Producto fixture guard 2 " + System.nanoTime(),
                "producto-fixture-guard2-" + System.nanoTime(), null, 50000L, null, null, Map.of(), true,
                null, null, empaque.id()));

        assertThatThrownBy(() -> service.delete(empaque.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("producto(s) activo(s)");
    }

    private Long crearCategoriaFixture() {
        Number catId = (Number) em.createNativeQuery("""
                INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug)
                VALUES (58, 'Categoria fixture guard', :slug)
                RETURNING cat_id
                """)
                .setParameter("slug", "categoria-fixture-guard-" + System.nanoTime())
                .getSingleResult();
        return catId.longValue();
    }
}
