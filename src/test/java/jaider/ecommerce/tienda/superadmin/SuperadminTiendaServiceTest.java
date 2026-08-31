package jaider.ecommerce.tienda.superadmin;

import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real (BD local, sin mocks) del flujo nuevo de superadmin: crear tienda en
 * borrador, guardar credenciales cifradas, ver su estado SIN el valor, y que activar() rechace
 * una tienda con integraciones incompletas.
 *
 * @Transactional: todo lo creado acá (incluido el superadmin de prueba) se revierte solo al
 * terminar cada test.
 */
@SpringBootTest
@Transactional
class SuperadminTiendaServiceTest {

    @Autowired
    private SuperadminTiendaService service;

    @PersistenceContext
    private EntityManager em;

    @Test
    void crearTienda_guardarCredenciales_yVerEstadoSinElValor() {
        long unico = System.nanoTime();
        // El actor de auditoría SIEMPRE es un superadmin en producción (el controller exige ese
        // rol) — su fila es visible bajo RLS sin importar el contexto de tenant activo, por eso
        // se crea uno de prueba en vez de reusar un admin normal de una tienda cualquiera.
        Long adminAuditorId = ((Number) em.createNativeQuery("""
                INSERT INTO admin_users (email, password, nombre, rol, tienda_id, activo)
                VALUES (:email, 'x', 'Superadmin Test', CAST('superadmin' AS rol_empleado), NULL, true)
                RETURNING id
                """)
                .setParameter("email", "superadmin-test-" + unico + "@example.com")
                .getSingleResult()).longValue();
        TenantProvisioningResult creada = service.crearBorrador(new CrearTiendaRequest(
                "Tienda Prueba", "Tienda Prueba SAS", "NIT-" + unico, "tienda-prueba-" + unico,
                "tienda-prueba-" + unico + ".test", "contacto" + unico + "@example.com", null, null,
                "admin" + unico + "@example.com", "ContraseñaSegura123", "Admin Prueba",
                null, null, null, null
        ));

        assertThat(creada.tenantId()).isNotNull();
        assertThat(creada.activada()).isFalse(); // sin ninguna integración configurada todavía

        Long tndId = creada.tenantId();

        // Aún no hay nada guardado — las 3 integraciones deben verse "no configurada".
        TiendaDetalleResponse antes = service.detalle(tndId);
        assertThat(antes.wompi()).allMatch(c -> !c.configurada());

        var estadoWompi = service.guardarWompi(tndId, new WompiCredencialesRequest(
                "pub_test_fake", null, "integrity_test_fake", "events_test_fake"), adminAuditorId);

        // "configurada" sí, pero el valor JAMÁS viaja en la respuesta.
        assertThat(estadoWompi).extracting(CampoEstadoResponse::campo)
                .contains("PUBLIC_KEY", "INTEGRITY_KEY", "EVENTS_KEY");
        var publicKeyEstado = estadoWompi.stream().filter(c -> c.campo().equals("PUBLIC_KEY")).findFirst().orElseThrow();
        assertThat(publicKeyEstado.configurada()).isTrue();
        assertThat(publicKeyEstado.origen()).isEqualTo("BD");
        assertThat(publicKeyEstado.actualizadoPorEmail()).isEqualTo("superadmin-test-" + unico + "@example.com");

        assertThat(estadoWompi.toString()).doesNotContain("pub_test_fake", "integrity_test_fake", "events_test_fake");

        // privateKey es opcional y no se mandó — debe seguir "no configurada".
        var privateKeyEstado = estadoWompi.stream().filter(c -> c.campo().equals("PRIVATE_KEY")).findFirst().orElseThrow();
        assertThat(privateKeyEstado.configurada()).isFalse();

        // Resend/Cloudinary siguen sin configurar — activar debe rechazar con 409, no con 500.
        assertThatThrownBy(() -> service.activar(tndId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Verifica primero");
    }
}
