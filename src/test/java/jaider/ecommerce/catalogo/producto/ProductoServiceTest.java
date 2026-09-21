package jaider.ecommerce.catalogo.producto;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.envio.EmpaqueRequest;
import jaider.ecommerce.tienda.envio.EmpaqueService;
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
 * Un PUT que solo actualiza una sección (ej. ficha técnica desde el panel) manda subId/empaqueId
 * en null cuando ese campo no le interesa a esa sección — antes eso se interpretaba igual que
 * "bórralo", así que cualquier actualización parcial le borraba la subcategoría/empaque al
 * producto sin que nadie lo pidiera (pasó de verdad: se detectó actualizando la ficha técnica de
 * un producto real en producción). Ahora null sin el flag limpiarSubId/limpiarEmpaqueId=true dejar
 * el valor actual intacto — el flag es la única forma de borrarlo a propósito (así sigue
 * funcionando el botón "Sin subcategoría"/"Sin empaque asignado" del panel).
 */
@SpringBootTest
@Transactional
class ProductoServiceTest {

    @Autowired
    private ProductoService service;

    @Autowired
    private EmpaqueService empaqueService;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void actualizacionParcialSinTocarSubIdNiEmpaqueId_losDejaIntactos() {
        TenantContext.set("1");
        tenantSupport.requireTenant(em);
        Long catId = crearCategoriaFixture();
        Long subId = crearSubcategoriaFixture(catId);
        Long empaqueId = empaqueService.create(new EmpaqueRequest(
                "Fixture ProductoServiceTest " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, (short) 0, true)
        ).id();

        ProductoResponse creado = service.create(new ProductoRequest(
                catId, subId, "Producto parcial " + System.nanoTime(),
                "producto-parcial-" + System.nanoTime(), null, 50000L, null, null,
                Map.of(), true, null, null, empaqueId, null, null));
        assertThat(creado.subId()).isEqualTo(subId);
        assertThat(creado.empaqueId()).isEqualTo(empaqueId);

        // Actualización parcial real: solo cambia la ficha técnica, todo lo demás null (como
        // manda cualquier PUT que no incluye esos campos).
        ProductoResponse actualizado = service.update(creado.id(), new ProductoRequest(
                null, null, null, null, null, null, null, null,
                Map.of("marca", "Duo Chic Studio"), null, null, null, null, null, null));

        assertThat(actualizado.subId()).as("subId no debe borrarse en una actualización parcial").isEqualTo(subId);
        assertThat(actualizado.empaqueId()).as("empaqueId no debe borrarse en una actualización parcial").isEqualTo(empaqueId);
        assertThat(actualizado.fichaTecnica()).containsEntry("marca", "Duo Chic Studio");
    }

    @Test
    void limpiarSubId_true_loBorraAPropósito() {
        TenantContext.set("1");
        tenantSupport.requireTenant(em);
        Long catId = crearCategoriaFixture();
        Long subId = crearSubcategoriaFixture(catId);
        ProductoResponse creado = service.create(new ProductoRequest(
                catId, subId, "Producto limpiar sub " + System.nanoTime(),
                "producto-limpiar-sub-" + System.nanoTime(), null, 50000L, null, null,
                Map.of(), true, null, null, null, null, null));
        assertThat(creado.subId()).isEqualTo(subId);

        ProductoResponse actualizado = service.update(creado.id(), new ProductoRequest(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, true, null));

        assertThat(actualizado.subId()).isNull();
    }

    @Test
    void limpiarEmpaqueId_true_loBorraAPropósito() {
        TenantContext.set("1");
        tenantSupport.requireTenant(em);
        Long catId = crearCategoriaFixture();
        Long empaqueId = empaqueService.create(new EmpaqueRequest(
                "Fixture limpiar empaque " + System.nanoTime(), (short) 30, (short) 20, (short) 12, 150, (short) 0, true)
        ).id();
        ProductoResponse creado = service.create(new ProductoRequest(
                catId, null, "Producto limpiar empaque " + System.nanoTime(),
                "producto-limpiar-empaque-" + System.nanoTime(), null, 50000L, null, null,
                Map.of(), true, null, null, empaqueId, null, null));
        assertThat(creado.empaqueId()).isEqualTo(empaqueId);

        ProductoResponse actualizado = service.update(creado.id(), new ProductoRequest(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, true));

        assertThat(actualizado.empaqueId()).isNull();
    }

    @Test
    void mandarUnSubIdNuevo_siLoCambia() {
        TenantContext.set("1");
        tenantSupport.requireTenant(em);
        Long catId = crearCategoriaFixture();
        Long subId1 = crearSubcategoriaFixture(catId);
        Long subId2 = crearSubcategoriaFixture(catId);
        ProductoResponse creado = service.create(new ProductoRequest(
                catId, subId1, "Producto cambia sub " + System.nanoTime(),
                "producto-cambia-sub-" + System.nanoTime(), null, 50000L, null, null,
                Map.of(), true, null, null, null, null, null));

        ProductoResponse actualizado = service.update(creado.id(), new ProductoRequest(
                null, subId2, null, null, null, null, null, null,
                null, null, null, null, null, null, null));

        assertThat(actualizado.subId()).isEqualTo(subId2);
    }

    private Long crearCategoriaFixture() {
        Number catId = (Number) em.createNativeQuery("""
                INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug)
                VALUES (1, 'Categoria fixture ProductoServiceTest', :slug)
                RETURNING cat_id
                """)
                .setParameter("slug", "categoria-fixture-pst-" + System.nanoTime())
                .getSingleResult();
        return catId.longValue();
    }

    private Long crearSubcategoriaFixture(Long catId) {
        Number subId = (Number) em.createNativeQuery("""
                INSERT INTO subcategorias (sub_cat_id, sub_nombre, sub_slug)
                VALUES (:catId, 'Subcategoria fixture ProductoServiceTest', :slug)
                RETURNING sub_id
                """)
                .setParameter("catId", catId)
                .setParameter("slug", "subcategoria-fixture-pst-" + System.nanoTime())
                .getSingleResult();
        return subId.longValue();
    }
}
