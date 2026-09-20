package jaider.ecommerce.infra.media;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real (BD local, sin mocks) del hallazgo de la guía de medios (2026-08-30): el
 * proxy verificaba correctamente el tenant vía RLS, pero nunca que el PRODUCTO dueño de la
 * imagen siguiera activo — cualquiera que adivinara un pi_id consecutivo podía ver fotos de
 * productos ocultos/en borrador, algo que PublicCatalogService.getProductoById() sí filtra pero
 * el proxy no filtraba en absoluto.
 *
 * @Transactional para que los fixtures nunca queden persistidos de verdad.
 */
@SpringBootTest
@Transactional
class MediaProxyServiceTest {

    @Autowired
    private MediaProxyService service;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void imagenDeProductoInactivo_da404_igualQueSiNoExistiera() {
        Long imgId = crearFixture(false);

        assertThatThrownBy(() -> service.fetchProductoImagen(1L, imgId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void imagenDeProductoActivo_pasaElChequeoDeVisibilidad() {
        Long imgId = crearFixture(true);

        // No hay red real en el test: una URL inalcanzable da 502 (Bad Gateway) en vez de 404 —
        // eso ya demuestra que pasó el chequeo de "producto activo" y llegó a intentar
        // descargar la imagen, que es lo único que este test necesita probar.
        assertThatThrownBy(() -> service.fetchProductoImagen(1L, imgId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(502));
    }

    private Long crearFixture(boolean productoActivo) {
        TenantContext.set("1");
        tenantSupport.requireTenant(em);

        Long catId = ((Number) em.createNativeQuery(
                "INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug) " +
                "VALUES (1, 'Media Test Cat', 'media-test-cat-" + System.nanoTime() + "') RETURNING cat_id")
                .getSingleResult()).longValue();

        Long prdId = ((Number) em.createNativeQuery(
                "INSERT INTO productos (prd_tnd_id, prd_cat_id, prd_nombre, prd_slug, prd_precio_centavos, prd_activo) " +
                "VALUES (1, :catId, 'Media Test Producto', 'media-test-producto-" + System.nanoTime() + "', 10000, :activo) " +
                "RETURNING prd_id")
                .setParameter("catId", catId)
                .setParameter("activo", productoActivo)
                .getSingleResult()).longValue();

        return ((Number) em.createNativeQuery(
                "INSERT INTO producto_imagenes (pi_prd_id, pi_url) " +
                "VALUES (:prdId, 'http://localhost:1/no-existe') RETURNING pi_id")
                .setParameter("prdId", prdId)
                .getSingleResult()).longValue();
    }
}
