package jaider.ecommerce.tienda;

import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningResult;
import jaider.ecommerce.tienda.superadmin.CrearTiendaRequest;
import jaider.ecommerce.tienda.superadmin.SuperadminTiendaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garantía central de la api key (2026-10-15): el valor real solo existe una vez, en texto
 * plano, justo al generarlo — después de eso solo se puede usar para identificar el tenant
 * (resolverTenantId), nunca volver a leer. Una key equivocada o de otro formato no resuelve a
 * ningún tenant (null, no una excepción) para que el interceptor pueda seguir cayendo al
 * dominio/X-Tenant-Id sin romper nada.
 */
@SpringBootTest
@Transactional
class TiendaApiKeyServiceTest {

    @Autowired
    private TiendaApiKeyService apiKeyService;

    @Autowired
    private SuperadminTiendaService superadminService;

    @Test
    void generarYResolver_funcionaYLaKeyViejaDejaDeServir() {
        long unico = System.nanoTime();
        TenantProvisioningResult creada = superadminService.crearBorrador(new CrearTiendaRequest(
                "Tienda ApiKey Test", "Tienda ApiKey Test SAS", "NIT-" + unico, "tienda-apikey-" + unico,
                "tienda-apikey-" + unico + ".test", "contacto" + unico + "@example.com", null, null,
                "admin" + unico + "@example.com", "ContraseñaSegura123", "Admin Prueba",
                null, null, null, null
        ));
        Long tndId = creada.tenantId();
        // findByApiKeyHashAndActivoTrue exige tienda activa a propósito (mismo criterio que
        // TenantEstadoCache para el resto de la plataforma) — una tienda recién creada queda
        // inactiva hasta pasar salud/aislamiento (ver TenantProvisioningService).
        superadminService.activar(tndId);

        String keyUno = apiKeyService.generar(tndId);
        assertThat(keyUno).startsWith("sk_tienda_");
        assertThat(apiKeyService.resolverTenantId(keyUno)).isEqualTo(tndId);

        // Rotar: la key anterior deja de servir de inmediato, la nueva sí funciona.
        String keyDos = apiKeyService.generar(tndId);
        assertThat(keyDos).isNotEqualTo(keyUno);
        assertThat(apiKeyService.resolverTenantId(keyUno)).isNull();
        assertThat(apiKeyService.resolverTenantId(keyDos)).isEqualTo(tndId);

        // Cualquier valor que no corresponda a ninguna key real: null, nunca una excepción (el
        // interceptor debe poder seguir cayendo al dominio/X-Tenant-Id sin que esto rompa nada).
        assertThat(apiKeyService.resolverTenantId("sk_tienda_inventada")).isNull();
        assertThat(apiKeyService.resolverTenantId("")).isNull();
        assertThat(apiKeyService.resolverTenantId(null)).isNull();
    }
}
