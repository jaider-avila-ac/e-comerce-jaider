package jaider.ecommerce.pedido;

import jaider.ecommerce.cupon.CrearCuponRequest;
import jaider.ecommerce.cupon.CuponService;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.usuario.cliente.ClienteDireccionRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PENDIENTE_DUO_CHIC: un cupón de 100% combinado con envío gratis puede dejar el TOTAL del pedido
 * en $0 — calcularDescuento() ya topa el descuento al subtotal, pero eso no alcanza para cubrir el
 * total (subtotal + envío). crearDesdeCarrito() debe rechazar ese checkout. Integración real
 * contra BD local, sin mocks.
 */
@SpringBootTest
@Transactional
class PedidoCreacionServiceCuponTest {

    @Autowired
    private PedidoCreacionService service;

    @Autowired
    private CuponService cuponService;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    private static final Long TND_ID = 1L; // Calzacaribe

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void cuponDe100PorCientoConEnvioGratis_rechazaElCheckoutPorTotalCero() {
        TenantContext.set(TND_ID.toString());
        tenantSupport.requireTenant(em);

        em.createNativeQuery("""
                UPDATE tiendas
                   SET tnd_envio_modo = 'contra_entrega',
                       tnd_envio_gratis_activo = true,
                       tnd_envio_gratis_desde_centavos = 10000000,
                       tnd_envio_costo_centavos = 1200000
                 WHERE tnd_id = :tndId
                """)
                .setParameter("tndId", TND_ID)
                .executeUpdate();

        Long usrId = crearUsuarioFixture();
        Long prdId = crearProductoFixture();
        Long varId = crearVarianteFixture(prdId);
        crearCarrito(usrId, prdId, varId, 10_000_000L); // 100.000 COP = justo el mínimo de envío gratis

        String adminEmail = crearAdminFixture();
        String codigo = "CIEN" + System.nanoTime();
        cuponService.crear(TND_ID,
                new CrearCuponRequest(codigo, false, "porcentaje", 100L, 1), adminEmail);

        ClienteDireccionRequest direccion = new ClienteDireccionRequest(
                "Calle fixture 123", null, "Magdalena", "Santa Marta", null, null,
                "Cliente Fixture", "3000000000", "470001");

        // Nota: crearDesdeCarrito() es @Transactional — en producción esa es la transacción raíz,
        // así que esta excepción la revierte entera (incluido el canje de más arriba, ya aplicado
        // en BD) ANTES de que el llamador la vea. No se verifica acá que el cupón "no quedó
        // consumido" porque este test corre dentro de la transacción envolvente de @Transactional
        // de la clase (para poder limpiar los fixtures solo), así que esa reversión puntual no es
        // observable desde el mismo test — la garantía de atomicidad es la misma que ya cubren
        // los tests de idempotencia del checkout (ver REAUDITORIA_FUNCIONAL_E_IDEMPOTENCIA.md).
        assertThatThrownBy(() ->
                service.crearDesdeCarrito(usrId, TND_ID, null, direccion, null, null, codigo))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("$0");
    }

    private String crearAdminFixture() {
        String email = "fixture-cupon-admin-" + System.nanoTime() + "@test.local";
        em.createNativeQuery("""
                INSERT INTO admin_users (email, password, nombre, rol, tienda_id, activo)
                VALUES (:email, 'x', 'Admin Fixture', CAST('admin' AS rol_empleado), :tndId, true)
                """)
                .setParameter("email", email)
                .setParameter("tndId", TND_ID)
                .executeUpdate();
        return email;
    }

    private Long crearUsuarioFixture() {
        Number usrId = (Number) em.createNativeQuery("""
                INSERT INTO usuarios (usr_email, usr_provider, usr_password_hash, usr_acepto_terminos, usr_tnd_id)
                VALUES (:email, CAST('EMAIL' AS auth_provider), 'x', true, :tndId)
                RETURNING usr_id
                """)
                .setParameter("email", "fixture-cupon-cero-" + System.nanoTime() + "@test.local")
                .setParameter("tndId", TND_ID)
                .getSingleResult();
        return usrId.longValue();
    }

    private Long crearProductoFixture() {
        Number catId = (Number) em.createNativeQuery("""
                INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug)
                VALUES (:tndId, 'Categoria fixture Cupon Cero', :slug)
                RETURNING cat_id
                """)
                .setParameter("tndId", TND_ID)
                .setParameter("slug", "categoria-fixture-cc-" + System.nanoTime())
                .getSingleResult();
        Number prdId = (Number) em.createNativeQuery("""
                INSERT INTO productos (prd_tnd_id, prd_cat_id, prd_nombre, prd_slug, prd_precio_centavos, prd_ficha_tecnica, prd_activo)
                VALUES (:tndId, :catId, 'Producto fixture Cupon Cero', :slug, 10000000, '{}'::jsonb, true)
                RETURNING prd_id
                """)
                .setParameter("tndId", TND_ID)
                .setParameter("catId", catId.longValue())
                .setParameter("slug", "producto-fixture-cc-" + System.nanoTime())
                .getSingleResult();
        return prdId.longValue();
    }

    private Long crearVarianteFixture(Long prdId) {
        Number varId = (Number) em.createNativeQuery("""
                INSERT INTO variantes (var_prd_id, var_stock, var_activo)
                VALUES (:prdId, 5, true)
                RETURNING var_id
                """)
                .setParameter("prdId", prdId)
                .getSingleResult();
        return varId.longValue();
    }

    private void crearCarrito(Long usrId, Long prdId, Long varId, long precioCentavos) {
        Number carId = (Number) em.createNativeQuery("""
                INSERT INTO carritos (car_usr_id) VALUES (:usrId) RETURNING car_id
                """)
                .setParameter("usrId", usrId)
                .getSingleResult();
        em.createNativeQuery("""
                INSERT INTO carrito_items (ci_car_id, ci_prd_id, ci_var_id, ci_cantidad, ci_precio_snap_centavos)
                VALUES (:carId, :prdId, :varId, 1, :precio)
                """)
                .setParameter("carId", carId.longValue())
                .setParameter("prdId", prdId)
                .setParameter("varId", varId)
                .setParameter("precio", precioCentavos)
                .executeUpdate();
    }
}
