package jaider.ecommerce.shared;

import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integración REAL contra la BD local (Postgres con RLS forzado, sin mocks) — cierra el punto 5
 * pendiente anotado en multitenant_plan.md: hasta ahora el aislamiento cruzado A/B solo se había
 * verificado a mano con curl durante cada fase, nunca quedó como suite automática que proteja
 * contra una regresión futura.
 *
 * Usa el tenant 1 (Calzacaribe, con catálogo real) y el tenant 2 ("Tienda Test B", que en la BD
 * local NO tiene catálogo propio — solo existe para este tipo de prueba cruzada). Cada test crea
 * su propio fixture del tenant 2 y verifica el aislamiento en ambas direcciones: que el tenant 1
 * nunca vea el fixture del tenant 2, y que el tenant 2 sí vea el suyo (para descartar que la
 * política simplemente esté bloqueando todo).
 *
 * @Transactional en cada test: Spring hace rollback automático al final del método, así que
 * estos fixtures nunca quedan persistidos de verdad en la BD local.
 */
@SpringBootTest
@Transactional
class RlsAislamientoIntegrationTest {

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void categorias_tenant1NuncaVeElFixtureDeTenant2_yTenant2SiVeElSuyo() {
        fijarTenant("2");
        Long catFixtureId = insertar(
                "INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug) " +
                "VALUES (2, 'RLS Test Fixture', 'rls-test-fixture-cat') RETURNING cat_id");

        fijarTenant("1");
        assertThat(distinctTndIds("categorias", "cat_tnd_id")).containsExactly(1L);
        assertThat(existePorId("categorias", "cat_id", catFixtureId)).isFalse();

        fijarTenant("2");
        assertThat(distinctTndIds("categorias", "cat_tnd_id")).containsExactly(2L);
        assertThat(existePorId("categorias", "cat_id", catFixtureId)).isTrue();
    }

    @Test
    void productos_tenant1NuncaVeElFixtureDeTenant2_yTenant2SiVeElSuyo() {
        fijarTenant("2");
        Long catId = insertar(
                "INSERT INTO categorias (cat_tnd_id, cat_nombre, cat_slug) " +
                "VALUES (2, 'RLS Test Cat Prod', 'rls-test-cat-prod') RETURNING cat_id");
        Long prdFixtureId = insertarConParametro(
                "INSERT INTO productos (prd_tnd_id, prd_cat_id, prd_nombre, prd_slug, prd_precio_centavos) " +
                "VALUES (2, :catId, 'RLS Test Producto', 'rls-test-producto', 10000) RETURNING prd_id",
                "catId", catId);

        fijarTenant("1");
        assertThat(distinctTndIds("productos", "prd_tnd_id")).containsExactly(1L);
        assertThat(existePorId("productos", "prd_id", prdFixtureId)).isFalse();

        fijarTenant("2");
        assertThat(existePorId("productos", "prd_id", prdFixtureId)).isTrue();
    }

    @Test
    void colecciones_tenant1NuncaVeElFixtureDeTenant2_yTenant2SiVeElSuyo() {
        fijarTenant("2");
        Long colFixtureId = insertar(
                "INSERT INTO colecciones (col_tnd_id, col_nombre, col_slug) " +
                "VALUES (2, 'RLS Test Coleccion', 'rls-test-coleccion') RETURNING col_id");

        fijarTenant("1");
        assertThat(existePorId("colecciones", "col_id", colFixtureId)).isFalse();

        fijarTenant("2");
        assertThat(existePorId("colecciones", "col_id", colFixtureId)).isTrue();
    }

    /** §10.6/§11.1 del plan: el mismo Google ID debe poder registrarse en dos tiendas distintas
     *  sin chocar (unique constraint por tenant, no global) — verificado a mano en su momento
     *  (ver multitenant_plan.md, Fase 0), acá queda como regresión automática. */
    @Test
    void mismoGoogleId_puedeRegistrarseEnDosTenantsDistintos_sinChocar() {
        String googleIdCompartido = "google-rls-test-" + System.nanoTime();

        fijarTenant("1");
        Long usr1 = insertarUsuarioGoogle("rls-test-1@example.com", googleIdCompartido, 1L);

        fijarTenant("2");
        Long usr2 = insertarUsuarioGoogle("rls-test-2@example.com", googleIdCompartido, 2L);

        assertThat(usr1).isNotEqualTo(usr2);

        // Y el aislamiento normal también aplica acá: cada tenant solo ve su propio usuario.
        fijarTenant("1");
        assertThat(existePorId("usuarios", "usr_id", usr2)).isFalse();
        fijarTenant("2");
        assertThat(existePorId("usuarios", "usr_id", usr1)).isFalse();
    }

    private Long insertarUsuarioGoogle(String email, String googleId, Long tndId) {
        return ((Number) em.createNativeQuery(
                "INSERT INTO usuarios (usr_email, usr_provider, usr_google_id, usr_tnd_id) " +
                "VALUES (:email, CAST('GOOGLE' AS auth_provider), :googleId, :tndId) RETURNING usr_id")
                .setParameter("email", email)
                .setParameter("googleId", googleId)
                .setParameter("tndId", tndId)
                .getSingleResult()).longValue();
    }

    private void fijarTenant(String tndId) {
        TenantContext.set(tndId);
        tenantSupport.requireTenant(em);
    }

    private Long insertar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private Long insertarConParametro(String sql, String nombreParametro, Object valor) {
        return ((Number) em.createNativeQuery(sql).setParameter(nombreParametro, valor).getSingleResult()).longValue();
    }

    private List<Long> distinctTndIds(String tabla, String columna) {
        List<?> raw = em.createNativeQuery("SELECT DISTINCT " + columna + " FROM " + tabla).getResultList();
        return raw.stream().map(o -> ((Number) o).longValue()).toList();
    }

    private boolean existePorId(String tabla, String columnaId, Long id) {
        Number count = (Number) em.createNativeQuery(
                "SELECT count(*) FROM " + tabla + " WHERE " + columnaId + " = :id")
                .setParameter("id", id)
                .getSingleResult();
        return count.longValue() > 0;
    }
}
