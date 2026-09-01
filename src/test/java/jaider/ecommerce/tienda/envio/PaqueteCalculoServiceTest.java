package jaider.ecommerce.tienda.envio;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real (BD local, sin mocks) de PLAN_INTEGRACION_ENVIA.md Fase 1: especificaciones
 * logísticas reutilizables + catálogo de empaques + cálculo del paquete de un carrito.
 *
 * @Transactional: todos los fixtures (tenant 1, Calzacaribe) se revierten solos al terminar cada
 * test.
 */
@SpringBootTest
@Transactional
class PaqueteCalculoServiceTest {

    @Autowired
    private EspecificacionLogisticaService especificacionService;

    @Autowired
    private EmpaqueService empaqueService;

    @Autowired
    private PaqueteCalculoService paqueteCalculoService;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void calculaPesoTotalYEligeElEmpaqueCorrectoSegunCantidad() {
        TenantContext.set("1");

        var esp = especificacionService.create(new EspecificacionLogisticaRequest(
                "Tenis estándar " + System.nanoTime(), 850, (short) 32, (short) 20, (short) 12, true));

        empaqueService.create(new EmpaqueRequest(
                "Pequeña " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, 1, 1, (short) 0, true));
        var mediana = empaqueService.create(new EmpaqueRequest(
                "Mediana " + System.nanoTime(), (short) 40, (short) 30, (short) 18, 250, 2, 2, (short) 1, true));
        empaqueService.create(new EmpaqueRequest(
                "Grande " + System.nanoTime(), (short) 50, (short) 40, (short) 30, 400, 3, null, (short) 2, true));

        Long prd1 = crearProductoConEspecificacion(esp.id());
        Long prd2 = crearProductoConEspecificacion(esp.id());

        // 2 artículos (1 de cada producto) -> caja Mediana, peso = 850+850+250 = 1950g
        PaqueteCalculado resultado = paqueteCalculoService.calcular(List.of(
                new ItemParaPaquete(prd1, 1),
                new ItemParaPaquete(prd2, 1)
        ));

        assertThat(resultado.pesoTotalGramos()).isEqualTo(850 + 850 + 250);
        assertThat(resultado.empaqueId()).isEqualTo(mediana.id());
        assertThat(resultado.largoCm()).isEqualTo((short) 40);
    }

    @Test
    void productoSinEspecificacionAsignada_dice400ClaroEnVezDeCalcularAlgoInventado() {
        TenantContext.set("1");
        Long prdSinEspec = crearProductoConEspecificacion(null);

        assertThatThrownBy(() -> paqueteCalculoService.calcular(List.of(new ItemParaPaquete(prdSinEspec, 1))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no tiene una especificación logística asignada");
    }

    @Test
    void sinNingunEmpaqueQueCubraLaCantidad_dice400ClaroEnVezDeElegirCualquiera() {
        TenantContext.set("1");
        var esp = especificacionService.create(new EspecificacionLogisticaRequest(
                "Espec sin empaque " + System.nanoTime(), 500, (short) 20, (short) 15, (short) 10, true));
        // Un solo empaque que cubre exactamente 1 artículo — pedir 5 no debe "ajustarse solo".
        empaqueService.create(new EmpaqueRequest(
                "Única " + System.nanoTime(), (short) 20, (short) 15, (short) 10, 100, 1, 1, (short) 0, true));
        Long prd = crearProductoConEspecificacion(esp.id());

        assertThatThrownBy(() -> paqueteCalculoService.calcular(List.of(new ItemParaPaquete(prd, 5))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No hay un empaque configurado");
    }

    private Long crearProductoConEspecificacion(Long especificacionId) {
        tenantSupport.requireTenant(em); // asegura SET LOCAL app.current_tnd_id aunque sea lo primero que corre en el test
        Long catId = ((Number) em.createNativeQuery(
                "INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug) " +
                "VALUES (1, 'Paquete Test Cat', 'paquete-test-cat-" + System.nanoTime() + "') RETURNING cat_id")
                .getSingleResult()).longValue();

        return ((Number) em.createNativeQuery(
                "INSERT INTO productos (prd_tnd_id, prd_cat_id, prd_nombre, prd_slug, prd_precio_centavos, prd_especificacion_id) " +
                "VALUES (1, :catId, 'Paquete Test Producto', 'paquete-test-producto-" + System.nanoTime() + "', 10000, :especId) " +
                "RETURNING prd_id")
                .setParameter("catId", catId)
                .setParameter("especId", especificacionId)
                .getSingleResult()).longValue();
    }
}
