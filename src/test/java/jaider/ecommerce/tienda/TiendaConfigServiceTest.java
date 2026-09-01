package jaider.ecommerce.tienda;

import jaider.ecommerce.shared.TenantSupport;
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
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real (BD local, sin mocks) de PLAN_INTEGRACION_ENVIA.md: el esquema acepta el modo
 * de envío "envia" (columna + CHECK de BD), pero activarlo exige que ya exista lo que el cálculo
 * real necesita (Fase 3) — credenciales de Envia configuradas y al menos una sucursal activa con
 * su dirección de origen completa. Calzacaribe (tienda 1) no tiene ninguna de las dos cosas
 * configuradas, así que sigue bloqueado en la práctica, pero ahora con un mensaje que refleja la
 * razón real (falta configuración) en vez del bloqueo genérico de "todavía no disponible".
 *
 * @Transactional: los cambios sobre la tienda 1 (Calzacaribe) se revierten solos al terminar.
 */
@SpringBootTest
@Transactional
class TiendaConfigServiceTest {

    @Autowired
    private TiendaConfigService service;

    @Autowired
    private SuperadminTiendaService superadminService;

    @Autowired
    private TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void modoEnvia_bloqueadoSinCredenciales_conMensajeClaroNoGenerico() {
        TenantContext.set("1");
        TiendaConfigResponse antes = service.getConfig();

        assertThatThrownBy(() -> service.updateConfig(new TiendaConfigRequest(
                "envia", null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("falta configurar el token de Envia");

        // No debe haber quedado a medio cambiar.
        assertThat(service.getConfig().envioModo()).isEqualTo(antes.envioModo());
    }

    @Test
    void modoFijo_siguePermitido_sinRegresion() {
        TenantContext.set("1");
        TiendaConfigResponse antes = service.getConfig();

        TiendaConfigResponse resultado = service.updateConfig(new TiendaConfigRequest(
                "fijo", null, null, null, null, null, null, null, null, null, null));

        assertThat(resultado.envioModo()).isEqualTo("fijo");

        // Deja todo como estaba para no afectar la BD local fuera de este test (por más que
        // @Transactional lo revierta solo, es más claro dejar la intención explícita acá).
        service.updateConfig(new TiendaConfigRequest(
                antes.envioModo(), null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void enviaAmbiente_sePuedeConfigurar_aunqueElModoEnviaSigaBloqueado() {
        TenantContext.set("1");

        TiendaConfigResponse resultado = service.updateConfig(new TiendaConfigRequest(
                null, null, null, null, null, null, null, null, null, null, "produccion"));

        assertThat(resultado.enviaAmbiente()).isEqualTo("produccion");

        // Valor inválido debe rechazarse con mensaje propio, no silenciarse.
        assertThatThrownBy(() -> service.updateConfig(new TiendaConfigRequest(
                null, null, null, null, null, null, null, null, null, null, "otro")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ambiente de Envia inválido");

        // Deja el ambiente como estaba (sandbox es el valor por defecto real de Calzacaribe).
        service.updateConfig(new TiendaConfigRequest(
                null, null, null, null, null, null, null, null, null, null, "sandbox"));
    }

    @Test
    void modoEnvia_bloqueadoSinSucursalConOrigen_auqueYaHayaCredenciales() {
        long unico = System.nanoTime();
        Long tndId = provisionarTiendaDePrueba(unico);
        superadminService.guardarEnvia(tndId, new EnviaCredencialesRequest("token-fake-" + unico, "secreto-" + unico),
                adminAuditorDePrueba(unico));

        TenantContext.set(tndId.toString());

        // Hay credenciales, pero ninguna sucursal con dirección de origen completa todavía.
        assertThatThrownBy(() -> service.updateConfig(new TiendaConfigRequest(
                "envia", null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ninguna sucursal activa");
    }

    // Corrección de auditoría (2026-09-01, tercera vuelta): sin WEBHOOK_SECRET, la cotización y
    // la generación de guías funcionan bien, pero TODOS los webhooks de Envia se rechazan de
    // entrada (EnvioWebhookService lo exige desde la auditoría anterior) — el seguimiento
    // automático de entregado/devuelto nunca actualizaría el pedido.
    @Test
    void modoEnvia_bloqueadoSinWebhookSecret_aunqueYaHayaTokenYSucursal() {
        long unico = System.nanoTime();
        Long tndId = provisionarTiendaDePrueba(unico);
        superadminService.guardarEnvia(tndId, new EnviaCredencialesRequest("token-fake-" + unico, null),
                adminAuditorDePrueba(unico));

        TenantContext.set(tndId.toString());
        tenantSupport.requireTenant(em);
        crearSucursalConOrigenCompleto(tndId);

        assertThatThrownBy(() -> service.updateConfig(new TiendaConfigRequest(
                "envia", null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("WEBHOOK_SECRET");
    }

    @Test
    void modoEnvia_sePermite_conCredencialesWebhookSecretYSucursalConOrigenCompleto() {
        long unico = System.nanoTime();
        Long tndId = provisionarTiendaDePrueba(unico);
        superadminService.guardarEnvia(tndId, new EnviaCredencialesRequest("token-fake-" + unico, "secreto-" + unico),
                adminAuditorDePrueba(unico));

        // sucursales tiene RLS forzado — hay que fijar el tenant en la sesión ANTES de insertar,
        // o la política WITH CHECK rechaza la fila.
        TenantContext.set(tndId.toString());
        tenantSupport.requireTenant(em);
        crearSucursalConOrigenCompleto(tndId);

        TiendaConfigResponse resultado = service.updateConfig(new TiendaConfigRequest(
                "envia", null, null, null, null, null, null, null, null, null, null));

        assertThat(resultado.envioModo()).isEqualTo("envia");
    }

    private Long provisionarTiendaDePrueba(long unico) {
        TenantProvisioningResult creada = superadminService.crearBorrador(new CrearTiendaRequest(
                "Tienda Config Test", "Tienda Config Test SAS", "NIT-" + unico, "tienda-config-test-" + unico,
                "tienda-config-test-" + unico + ".test", "contacto" + unico + "@example.com", null, null,
                "admin" + unico + "@example.com", "ContraseñaSegura123", "Admin Prueba",
                null, null, null, null
        ));
        return creada.tenantId();
    }

    private Long adminAuditorDePrueba(long unico) {
        // Igual patrón que SuperadminTiendaServiceTest: el actor de auditoría siempre es un
        // superadmin real, visible bajo RLS sin importar el tenant activo.
        return ((Number) em.createNativeQuery("""
                INSERT INTO admin_users (email, password, nombre, rol, tienda_id, activo)
                VALUES (:email, 'x', 'Superadmin Test', CAST('superadmin' AS rol_empleado), NULL, true)
                RETURNING id
                """)
                .setParameter("email", "superadmin-config-test-" + unico + "@example.com")
                .getSingleResult()).longValue();
    }

    private void crearSucursalConOrigenCompleto(Long tndId) {
        em.createNativeQuery("""
                INSERT INTO sucursales (suc_tnd_id, suc_nombre, suc_activo,
                        suc_envio_origen_nombre, suc_envio_origen_telefono, suc_envio_origen_direccion,
                        suc_envio_origen_departamento, suc_envio_origen_municipio, suc_envio_origen_codigo_postal,
                        suc_creado_en)
                VALUES (:tndId, 'Principal', true,
                        'Ampaz Studio', '3000000000', 'Calle Falsa 123',
                        'Magdalena', 'Santa Marta', '470001',
                        now())
                """)
                .setParameter("tndId", tndId)
                .executeUpdate();
    }
}
