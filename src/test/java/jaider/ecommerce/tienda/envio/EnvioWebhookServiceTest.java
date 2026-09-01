package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningResult;
import jaider.ecommerce.tienda.superadmin.CrearTiendaRequest;
import jaider.ecommerce.tienda.superadmin.EnviaCredencialesRequest;
import jaider.ecommerce.tienda.superadmin.SuperadminTiendaService;
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
 * Integración real (BD local, sin mocks) del webhook de seguimiento — PLAN_INTEGRACION_ENVIA.md,
 * Fase 5. No llama a Envia de verdad (esta clase solo REACCIONA a un payload ya recibido) — la
 * llamada real de Envia hacia nuestro backend no se puede probar del mismo modo que cotizar/
 * generar guía (esas somos NOSOTROS llamando a Envia; acá es al revés), así que esta prueba
 * simula el payload que Envia mandaría.
 *
 * Tienda + pedido de prueba propios (no Calzacaribe, que no tiene Envia configurado) —
 * @Transactional revierte todo solo al terminar cada test.
 */
@SpringBootTest
@Transactional
class EnvioWebhookServiceTest {

    @Autowired
    private EnvioWebhookService service;

    @Autowired
    private SuperadminTiendaService superadminService;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void statusDelivered_marcaEntregado_yEsIdempotente() {
        long unico = System.nanoTime();
        Long tndId = tiendaConEnviaConfigurado(unico);
        String tracking = "TRACK-DELIVERED-" + unico;
        Long pedId = crearPedidoDePrueba(tndId, tracking);

        service.procesar(tndId, Map.of("trackingNumber", tracking, "status", "Delivered"), null);
        assertThat(estadoDe(pedId)).isEqualTo("entregado");

        // Un segundo webhook con el mismo evento (reintento típico) no debe fallar ni cambiar nada.
        service.procesar(tndId, Map.of("trackingNumber", tracking, "status", "Delivered"), null);
        assertThat(estadoDe(pedId)).isEqualTo("entregado");
    }

    @Test
    void statusReturned_marcaDevuelto() {
        long unico = System.nanoTime();
        Long tndId = tiendaConEnviaConfigurado(unico);
        String tracking = "TRACK-RETURNED-" + unico;
        Long pedId = crearPedidoDePrueba(tndId, tracking);

        service.procesar(tndId, Map.of("trackingNumber", tracking, "status", "Returned to sender"), null);
        assertThat(estadoDe(pedId)).isEqualTo("devuelto");
    }

    @Test
    void statusNoAccionable_noCambiaNada() {
        long unico = System.nanoTime();
        Long tndId = tiendaConEnviaConfigurado(unico);
        String tracking = "TRACK-INTRANSIT-" + unico;
        Long pedId = crearPedidoDePrueba(tndId, tracking);

        service.procesar(tndId, Map.of("trackingNumber", tracking, "status", "In Transit"), null);
        assertThat(estadoDe(pedId)).isEqualTo("pagado");
    }

    @Test
    void trackingDesconocido_noLanza() {
        long unico = System.nanoTime();
        Long tndId = tiendaConEnviaConfigurado(unico);
        // No debe lanzar excepción aunque el tracking no exista — un webhook siempre "se acepta".
        service.procesar(tndId, Map.of("trackingNumber", "NO-EXISTE-" + unico, "status", "Delivered"), null);
    }

    private Long tiendaConEnviaConfigurado(long unico) {
        TenantProvisioningResult creada = superadminService.crearBorrador(new CrearTiendaRequest(
                "Tienda Webhook Test", "Tienda Webhook Test SAS", "NIT-" + unico, "tienda-webhook-test-" + unico,
                "tienda-webhook-test-" + unico + ".test", "contacto" + unico + "@example.com", null, null,
                "admin" + unico + "@example.com", "ContraseñaSegura123", "Admin Prueba",
                null, null, null, null
        ));
        Long tndId = creada.tenantId();

        Number adminAuditorId = (Number) em.createNativeQuery(
                "SELECT id FROM admin_users WHERE rol = 'superadmin' ORDER BY id LIMIT 1"
        ).getSingleResult();
        superadminService.guardarEnvia(tndId, new EnviaCredencialesRequest("token-fake-" + unico, null),
                adminAuditorId.longValue());
        return tndId;
    }

    private Long crearPedidoDePrueba(Long tndId, String tracking) {
        TenantContext.set(tndId.toString());
        Number usrId = (Number) em.createNativeQuery("""
                SELECT usr_id FROM usuarios WHERE usr_tnd_id = :tndId LIMIT 1
                """).setParameter("tndId", tndId).getResultList().stream().findFirst().orElse(null);
        if (usrId == null) {
            usrId = (Number) em.createNativeQuery("""
                    INSERT INTO usuarios (usr_tnd_id, usr_email, usr_password_hash, usr_provider, usr_activo)
                    VALUES (:tndId, :email, 'x', CAST('LOCAL' AS auth_provider), true) RETURNING usr_id
                    """).setParameter("tndId", tndId).setParameter("email", "cliente-webhook-" + tracking + "@example.com")
                    .getSingleResult();
        }
        Number pedId = (Number) em.createNativeQuery("""
                INSERT INTO pedidos (ped_tnd_id, ped_usr_id, ped_numero, ped_dir_snapshot, ped_subtotal_centavos,
                                      ped_total_centavos, ped_estado, ped_codigo_rastreo, ped_envia_shipment_id, ped_transportadora)
                VALUES (:tndId, :usrId, :numero, '{}', 10000000, 10000000, CAST('pagado' AS estado_pedido),
                        :tracking, :tracking, 'coordinadora')
                RETURNING ped_id
                """)
                .setParameter("tndId", tndId).setParameter("usrId", usrId)
                .setParameter("numero", "P" + (Math.abs(tracking.hashCode())))
                .setParameter("tracking", tracking)
                .getSingleResult();
        return pedId.longValue();
    }

    private String estadoDe(Long pedId) {
        return em.createNativeQuery("SELECT CAST(ped_estado AS text) FROM pedidos WHERE ped_id = :id")
                .setParameter("id", pedId).getSingleResult().toString();
    }
}
