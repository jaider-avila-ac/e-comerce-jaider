package jaider.ecommerce.tienda;

import jaider.ecommerce.shared.interceptor.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real (BD local, sin mocks) de PLAN_INTEGRACION_ENVIA.md Fase 0: el esquema ya
 * acepta el modo de envío "envia" (columna + CHECK de BD), pero activarlo debe seguir bloqueado
 * a nivel de aplicación hasta que la Fase 3 construya el cálculo real — si no, el checkout caería
 * en silencio al costo fijo, como si fuera un envío calculado de verdad.
 *
 * @Transactional: los cambios sobre la tienda 1 (Calzacaribe) se revierten solos al terminar.
 */
@SpringBootTest
@Transactional
class TiendaConfigServiceTest {

    @Autowired
    private TiendaConfigService service;

    @AfterEach
    void limpiarContexto() {
        TenantContext.clear();
    }

    @Test
    void modoEnvia_bloqueadoHastaFase3_conMensajeClaroNoGenerico() {
        TenantContext.set("1");
        TiendaConfigResponse antes = service.getConfig();

        assertThatThrownBy(() -> service.updateConfig(new TiendaConfigRequest(
                "envia", null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("todavía no está disponible");

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
}
