package jaider.ecommerce.catalogo.carrito;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mínimo para envío gratis tiene que funcionar también en modo "contra entrega" (el de Duo
 * Chic): por debajo del mínimo el cliente le paga al transportador al recibir; al alcanzarlo la
 * tienda asume el envío y el cliente no paga nada. Integración real contra la BD local, sin mocks.
 */
@SpringBootTest
@Transactional
class CarritoServiceEnvioGratisTest {

    @Autowired
    private CarritoService service;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void contraEntrega_bajoElMinimo_sigueSiendoContraEntregaYMuestraLoQueFalta() {
        Map<String, Object> carrito = carritoConSubtotalPesos("contra_entrega", true, 500_000L, 300_000L);

        assertThat(carrito.get("envio_contra_entrega")).isEqualTo(true);
        assertThat(carrito.get("envio_gratis_activo")).isEqualTo(true);
        assertThat(carrito.get("envio_gratis_alcanzado")).isEqualTo(false);
        assertThat(carrito.get("faltante_envio_gratis")).isEqualTo(200_000L);
        assertThat(carrito.get("envio")).isEqualTo(0L);
    }

    @Test
    void contraEntrega_alcanzandoElMinimo_laTiendaAsumeElEnvioYYaNoEsContraEntrega() {
        Map<String, Object> carrito = carritoConSubtotalPesos("contra_entrega", true, 500_000L, 500_000L);

        assertThat(carrito.get("envio_contra_entrega")).isEqualTo(false);
        assertThat(carrito.get("envio_gratis_alcanzado")).isEqualTo(true);
        assertThat(carrito.get("faltante_envio_gratis")).isEqualTo(0L);
        assertThat(carrito.get("envio")).isEqualTo(0L);
    }

    @Test
    void contraEntrega_conEnvioGratisDesactivado_nuncaSeConsideraGratis() {
        Map<String, Object> carrito = carritoConSubtotalPesos("contra_entrega", false, 500_000L, 900_000L);

        assertThat(carrito.get("envio_contra_entrega")).isEqualTo(true);
        assertThat(carrito.get("envio_gratis_activo")).isEqualTo(false);
        assertThat(carrito.get("envio_gratis_alcanzado")).isEqualTo(false);
    }

    @Test
    void costoFijo_siguePasandoIgual_cobraElEnvioBajoElMinimoYNoLoCobraAlAlcanzarlo() {
        Map<String, Object> bajo = carritoConSubtotalPesos("fijo", true, 500_000L, 100_000L);
        assertThat(bajo.get("envio_contra_entrega")).isEqualTo(false);
        assertThat((Long) bajo.get("envio")).isGreaterThan(0L);

        Map<String, Object> alcanzado = carritoConSubtotalPesos("fijo", true, 500_000L, 600_000L);
        assertThat(alcanzado.get("envio")).isEqualTo(0L);
        assertThat(alcanzado.get("envio_gratis_alcanzado")).isEqualTo(true);
    }

    private Map<String, Object> carritoConSubtotalPesos(String modo, boolean gratisActivo, long minimoPesos, long subtotalPesos) {
        TenantContext.set("1");
        tenantSupport.requireTenant(em);

        em.createNativeQuery("""
                UPDATE tiendas
                   SET tnd_envio_modo = :modo,
                       tnd_envio_gratis_activo = :activo,
                       tnd_envio_gratis_desde_centavos = :minimo,
                       tnd_envio_costo_centavos = 1200000
                 WHERE tnd_id = 1
                """)
                .setParameter("modo", modo)
                .setParameter("activo", gratisActivo)
                .setParameter("minimo", minimoPesos * 100L)
                .executeUpdate();

        Long usrId = crearUsuarioFixture();
        Long prdId = crearProductoFixture();
        service.getCarrito(usrId, 1L); // crea el carrito del usuario

        Number carId = (Number) em.createNativeQuery("SELECT car_id FROM carritos WHERE car_usr_id = :u")
                .setParameter("u", usrId).getSingleResult();
        em.createNativeQuery("""
                INSERT INTO carrito_items (ci_car_id, ci_prd_id, ci_var_id, ci_cantidad, ci_precio_snap_centavos)
                VALUES (:carId, :prdId, NULL, 1, :precio)
                """)
                .setParameter("carId", carId.longValue())
                .setParameter("prdId", prdId)
                .setParameter("precio", subtotalPesos * 100L)
                .executeUpdate();

        return service.getCarrito(usrId, 1L);
    }

    private Long crearUsuarioFixture() {
        Number usrId = (Number) em.createNativeQuery("""
                INSERT INTO usuarios (usr_email, usr_provider, usr_password_hash, usr_acepto_terminos, usr_tnd_id)
                VALUES (:email, CAST('EMAIL' AS auth_provider), 'x', true, 1)
                RETURNING usr_id
                """)
                .setParameter("email", "fixture-envio-gratis-" + System.nanoTime() + "@test.local")
                .getSingleResult();
        return usrId.longValue();
    }

    private Long crearProductoFixture() {
        Number catId = (Number) em.createNativeQuery("""
                INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug)
                VALUES (1, 'Categoria fixture EnvioGratis', :slug)
                RETURNING cat_id
                """)
                .setParameter("slug", "categoria-fixture-eg-" + System.nanoTime())
                .getSingleResult();
        Number prdId = (Number) em.createNativeQuery("""
                INSERT INTO productos (prd_tnd_id, prd_cat_id, prd_nombre, prd_slug, prd_precio_centavos, prd_ficha_tecnica, prd_activo)
                VALUES (1, :catId, 'Producto fixture EnvioGratis', :slug, 5000000, '{}'::jsonb, true)
                RETURNING prd_id
                """)
                .setParameter("catId", catId.longValue())
                .setParameter("slug", "producto-fixture-eg-" + System.nanoTime())
                .getSingleResult();
        return prdId.longValue();
    }
}
