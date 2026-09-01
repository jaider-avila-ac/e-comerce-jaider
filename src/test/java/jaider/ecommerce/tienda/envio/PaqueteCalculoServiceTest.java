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
 * Integración real (BD local, sin mocks) de PLAN_INTEGRACION_ENVIA.md Fase 1: arma el arreglo
 * {@code packages[]} (un renglón por empaque distinto del carrito, cantidades agrupadas) tal
 * como lo espera la API real de Envia.com — sin sumar nada nosotros mismos.
 *
 * @Transactional: todos los fixtures (tenant 1, Calzacaribe) se revierten solos al terminar cada
 * test.
 */
@SpringBootTest
@Transactional
class PaqueteCalculoServiceTest {

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
    void dosProductosConElMismoEmpaque_seAgrupanEnUnSoloRenglonConLaCantidadSumada() {
        TenantContext.set("1");
        var empaque = empaqueService.create(new EmpaqueRequest(
                "Mediana " + System.nanoTime(), (short) 40, (short) 30, (short) 18, 250, (short) 0, true));

        Long prd1 = crearProductoConEmpaque(empaque.id());
        Long prd2 = crearProductoConEmpaque(empaque.id());

        List<PaqueteCalculado> resultado = paqueteCalculoService.calcular(List.of(
                new ItemParaPaquete(prd1, 2),
                new ItemParaPaquete(prd2, 3)
        ));

        // Mismo empaque en ambos productos -> UN solo renglón con cantidad 2+3=5, listo para
        // mandarlo tal cual al packages[] de Envia (ellos multiplican peso x cantidad, no acá).
        assertThat(resultado).hasSize(1);
        PaqueteCalculado renglon = resultado.get(0);
        assertThat(renglon.empaqueId()).isEqualTo(empaque.id());
        assertThat(renglon.cantidad()).isEqualTo(5);
        assertThat(renglon.pesoGramosPorUnidad()).isEqualTo(250);
        assertThat(renglon.largoCm()).isEqualTo((short) 40);
    }

    @Test
    void dosProductosConEmpaquesDistintos_danDosRenglonesSeparados() {
        TenantContext.set("1");
        var pequena = empaqueService.create(new EmpaqueRequest(
                "Pequeña " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, (short) 0, true));
        var grande = empaqueService.create(new EmpaqueRequest(
                "Grande " + System.nanoTime(), (short) 50, (short) 40, (short) 30, 400, (short) 1, true));

        Long prdPequeno = crearProductoConEmpaque(pequena.id());
        Long prdGrande = crearProductoConEmpaque(grande.id());

        List<PaqueteCalculado> resultado = paqueteCalculoService.calcular(List.of(
                new ItemParaPaquete(prdPequeno, 1),
                new ItemParaPaquete(prdGrande, 2)
        ));

        assertThat(resultado).hasSize(2);
        assertThat(resultado).anyMatch(r -> r.empaqueId().equals(pequena.id()) && r.cantidad() == 1);
        assertThat(resultado).anyMatch(r -> r.empaqueId().equals(grande.id()) && r.cantidad() == 2);
    }

    @Test
    void productoSinEmpaqueAsignado_dice400ClaroEnVezDeCalcularAlgoInventado() {
        TenantContext.set("1");
        Long prdSinEmpaque = crearProductoConEmpaque(null);

        assertThatThrownBy(() -> paqueteCalculoService.calcular(List.of(new ItemParaPaquete(prdSinEmpaque, 1))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no tiene un empaque asignado");
    }

    private Long crearProductoConEmpaque(Long empaqueId) {
        tenantSupport.requireTenant(em); // asegura SET LOCAL app.current_tnd_id aunque sea lo primero que corre en el test
        Long catId = ((Number) em.createNativeQuery(
                "INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug) " +
                "VALUES (1, 'Paquete Test Cat', 'paquete-test-cat-" + System.nanoTime() + "') RETURNING cat_id")
                .getSingleResult()).longValue();

        return ((Number) em.createNativeQuery(
                "INSERT INTO productos (prd_tnd_id, prd_cat_id, prd_nombre, prd_slug, prd_precio_centavos, prd_empaque_id) " +
                "VALUES (1, :catId, 'Paquete Test Producto', 'paquete-test-producto-" + System.nanoTime() + "', 10000, :empaqueId) " +
                "RETURNING prd_id")
                .setParameter("catId", catId)
                .setParameter("empaqueId", empaqueId)
                .getSingleResult()).longValue();
    }
}
