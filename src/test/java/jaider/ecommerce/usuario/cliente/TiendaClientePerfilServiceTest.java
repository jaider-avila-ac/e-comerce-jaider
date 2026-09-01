package jaider.ecommerce.usuario.cliente;

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
 * Integración real (BD local, sin mocks) de PLAN_INTEGRACION_ENVIA.md, Fase 3: una tienda en
 * modo 'envia' necesita municipio/departamento/código postal/contacto completos en cada
 * dirección de cliente para poder calcular el envío real después — sin esto, una dirección a
 * medias pasaría el checkout en silencio (ver también PedidoCreacionServiceTest, que cubre el
 * mismo gate del lado del checkout).
 *
 * Se usa la tienda "Ampaz Studio" (tenant 58, envio_modo='envia', creada como fixture real y
 * persistente para las pruebas de Envia) y Calzacaribe (tenant 1, contra_entrega) para probar
 * que NO hay regresión ahí.
 *
 * @Transactional: todo lo creado acá (usuario de prueba, direcciones) se revierte solo.
 */
@SpringBootTest
@Transactional
class TiendaClientePerfilServiceTest {

    private static final long AMPAZ_STUDIO_TND_ID = 58L;
    private static final long CALZACARIBE_TND_ID = 1L;

    @Autowired
    private TiendaClientePerfilService service;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void addDireccion_rechazaIncompleta_enTiendaModoEnvia() {
        Long usrId = crearUsuarioDePrueba(AMPAZ_STUDIO_TND_ID);

        // Falta código postal, municipio, etc. — solo trae la calle.
        ClienteDireccionRequest incompleta = new ClienteDireccionRequest(
                "Calle 1 # 2-3", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.addDireccion(usrId, AMPAZ_STUDIO_TND_ID, incompleta))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("calcula el envío real");
    }

    @Test
    void addDireccion_aceptaCompleta_enTiendaModoEnvia() {
        Long usrId = crearUsuarioDePrueba(AMPAZ_STUDIO_TND_ID);

        ClienteDireccionRequest completa = new ClienteDireccionRequest(
                "Calle 100 # 10-20", "Apto 501", "Cundinamarca", "Bogotá", "Chapinero", "501",
                "Cliente Prueba", "3009876543", "110111");

        var direcciones = service.addDireccion(usrId, AMPAZ_STUDIO_TND_ID, completa);

        assertThat(direcciones).hasSize(1);
        assertThat(direcciones.get(0).get("codigo_postal")).isEqualTo("110111");
    }

    @Test
    void addDireccion_incompleta_siguePermitidaEnCalzacaribe_sinRegresion() {
        // Calzacaribe (contra_entrega) no exige nada de esto — no debe verse afectada.
        Long usrId = crearUsuarioDePrueba(CALZACARIBE_TND_ID);

        ClienteDireccionRequest incompleta = new ClienteDireccionRequest(
                "Calle 1 # 2-3", null, null, null, null, null, null, null, null);

        var direcciones = service.addDireccion(usrId, CALZACARIBE_TND_ID, incompleta);
        assertThat(direcciones).hasSize(1);
    }

    private Long crearUsuarioDePrueba(long tndId) {
        long unico = System.nanoTime();
        TenantContext.set(String.valueOf(tndId));
        tenantSupport.requireTenant(em);
        Number usrId = (Number) em.createNativeQuery("""
                INSERT INTO usuarios (usr_tnd_id, usr_email, usr_password_hash, usr_provider, usr_activo)
                VALUES (:tndId, :email, 'x', CAST('LOCAL' AS auth_provider), true)
                RETURNING usr_id
                """)
                .setParameter("tndId", tndId)
                .setParameter("email", "cliente-test-" + unico + "@example.com")
                .getSingleResult();
        return usrId.longValue();
    }
}
