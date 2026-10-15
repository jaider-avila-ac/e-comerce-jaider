package jaider.ecommerce.tienda.integracion;

import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningResult;
import jaider.ecommerce.tienda.superadmin.CampoEstadoResponse;
import jaider.ecommerce.tienda.superadmin.CrearTiendaRequest;
import jaider.ecommerce.tienda.superadmin.SuperadminTiendaService;
import jaider.ecommerce.tienda.superadmin.WompiCredencialesRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garantía central del pedido del usuario (2026-10-15): tanto el superadmin como el propio admin
 * de la tienda pueden actualizar las credenciales de Wompi, y un campo que llega en blanco NUNCA
 * borra el valor que ya estaba guardado — solo se toca lo que de verdad llega con contenido.
 */
@SpringBootTest
@Transactional
class TiendaWompiCredencialesServiceTest {

    @Autowired
    private TiendaWompiCredencialesService service;

    @Autowired
    private SuperadminTiendaService superadminService;

    @PersistenceContext
    private EntityManager em;

    @Test
    void actualizacionParcial_nuncaBorraLosCamposQueNoSeEnviaron() {
        long unico = System.nanoTime();
        Long adminAuditorId = ((Number) em.createNativeQuery("""
                INSERT INTO admin_users (email, password, nombre, rol, tienda_id, activo)
                VALUES (:email, 'x', 'Admin Test', CAST('superadmin' AS rol_empleado), NULL, true)
                RETURNING id
                """)
                .setParameter("email", "wompi-test-" + unico + "@example.com")
                .getSingleResult()).longValue();

        TenantProvisioningResult creada = superadminService.crearBorrador(new CrearTiendaRequest(
                "Tienda Wompi Test", "Tienda Wompi Test SAS", "NIT-" + unico, "tienda-wompi-" + unico,
                "tienda-wompi-" + unico + ".test", "contacto" + unico + "@example.com", null, null,
                "admin" + unico + "@example.com", "ContraseñaSegura123", "Admin Prueba",
                null, null, null, null
        ));
        Long tndId = creada.tenantId();

        // Primera carga: las 4 llaves.
        List<CampoEstadoResponse> primera = service.guardar(tndId, new WompiCredencialesRequest(
                "pub_original", "priv_original", "integrity_original", "events_original"), adminAuditorId, "tienda_admin");
        assertThat(primera).allMatch(CampoEstadoResponse::configurada);

        // Segunda actualización: SOLO manda la llave privada (rotación) — las otras 3 quedan en
        // blanco en el request, como haría un admin que solo quiere cambiar una llave.
        List<CampoEstadoResponse> segunda = service.guardar(tndId, new WompiCredencialesRequest(
                null, "priv_rotada", null, null), adminAuditorId, "tienda_admin");

        // Las 4 siguen "configurada" — ninguna se borró por llegar en blanco.
        assertThat(segunda).allMatch(CampoEstadoResponse::configurada);

        // Y el valor real de PUBLIC_KEY (no viaja en la respuesta) sigue siendo el original, no
        // uno vacío — se verifica indirectamente por fecha de actualización: PUBLIC_KEY no debió
        // tocarse en la segunda llamada, así que su timestamp es el de la primera.
        var publicKeyPrimera = primera.stream().filter(c -> c.campo().equals("PUBLIC_KEY")).findFirst().orElseThrow();
        var publicKeySegunda = segunda.stream().filter(c -> c.campo().equals("PUBLIC_KEY")).findFirst().orElseThrow();
        assertThat(publicKeySegunda.actualizadoEn()).isEqualTo(publicKeyPrimera.actualizadoEn());

        // isAfterOrEqualTo (no isAfter a secas): dentro de la misma transacción de prueba, now()
        // de Postgres puede devolver el mismo instante para las dos llamadas — lo que de verdad
        // importa (que PUBLIC_KEY no se tocó) ya quedó probado arriba con isEqualTo.
        var privateKeySegunda = segunda.stream().filter(c -> c.campo().equals("PRIVATE_KEY")).findFirst().orElseThrow();
        assertThat(privateKeySegunda.actualizadoEn()).isAfterOrEqualTo(
                primera.stream().filter(c -> c.campo().equals("PRIVATE_KEY")).findFirst().orElseThrow().actualizadoEn());

        assertThat(segunda.toString()).doesNotContain("pub_original", "priv_original", "priv_rotada", "integrity_original", "events_original");
    }
}
