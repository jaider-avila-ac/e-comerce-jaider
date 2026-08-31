package jaider.ecommerce.pago;

import jaider.ecommerce.tienda.integracion.TenantIntegrationResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración REAL (sin mocks, `.env` local de verdad, sin llamar a Wompi por red) del orden
 * obligatorio de §7.3 del plan: "resolver el tenant ANTES de verificar la firma". Cierra el punto
 * 5 pendiente de multitenant_plan.md — hasta ahora esto solo se había probado a mano con curl.
 *
 * Firma el payload con la MISMA fórmula que {@code WompiService.verificarWebhook()}
 * (sha256(propiedades + timestamp + eventsKey)) usando la llave de eventos REAL de Calzacaribe
 * ya configurada (`WOMPI_CALZADO_CARIBE_EVENTS_KEY`) — nunca se imprime ni se compara con
 * `isEqualTo` contra un literal, solo se usa para calcular el checksum que viaja en el payload.
 *
 * @Transactional para que cualquier fila que llegara a escribirse (no debería, en ninguno de
 * estos 3 casos) se revierta sola al terminar el test.
 */
@SpringBootTest
@Transactional
class PagoWebhookServiceSecurityTest {

    @Autowired
    private PagoWebhookService webhookService;

    @Autowired
    private TenantIntegrationResolver integrationResolver;

    @Test
    void referenciaDeTenantSinCredencialesWompi_lanza400AntesDeVerificarFirma() {
        // Tenant 2 ("Tienda Test B") no tiene NINGUNA credencial Wompi configurada a propósito
        // (ver Fase 1) — ni siquiera llega a intentar verificar una firma.
        Map<String, Object> evento = construirEvento("ECM-2-1-fixture", "checksum-cualquiera",
                List.of("transaction.id"), 1_000_000L);

        assertThatThrownBy(() -> webhookService.procesarWebhook(evento))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void referenciaDeTenant1_firmaAlteradaTrasFirmarConLaLlaveReal_lanza401() {
        long timestamp = 1_000_000L;
        String referencia = "ECM-1-999999-fixture";
        Map<String, Object> evento = construirEvento(referencia,
                firmarConLlaveReal(referencia, timestamp) + "0", // altera un dígito del checksum válido
                List.of("transaction.id"), timestamp);

        assertThatThrownBy(() -> webhookService.procesarWebhook(evento))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void referenciaDeTenant1_firmaValidaConLaLlaveReal_pasaLaVerificacionYNoRevienta() {
        // El pago no existe (referencia inventada) — el flujo real simplemente loguea y retorna,
        // sin excepción. Lo que este test demuestra es que la firma calculada con la llave REAL
        // de Calzacaribe SÍ es aceptada (si no lo fuera, este caso también daría 401, igual que
        // el test anterior).
        long timestamp = 2_000_000L;
        String referencia = "ECM-1-999999-fixture";
        Map<String, Object> evento = construirEvento(referencia,
                firmarConLlaveReal(referencia, timestamp), List.of("transaction.id"), timestamp);

        assertThatCode(() -> webhookService.procesarWebhook(evento)).doesNotThrowAnyException();
    }

    /** Misma fórmula que WompiService.verificarWebhook(): sha256(props + timestamp + eventsKey). */
    private String firmarConLlaveReal(String referencia, long timestamp) {
        String eventsKey = integrationResolver.paymentCredentials(1L).eventsKey();
        String contenido = "tx_1" + timestamp + eventsKey; // "transaction.id" resuelve a tx.get("id")
        return sha256(contenido);
    }

    private Map<String, Object> construirEvento(String referencia, String checksum,
                                                 List<String> propiedades, long timestamp) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("id", "tx_1");
        tx.put("reference", referencia);
        tx.put("status", "APPROVED");
        tx.put("amount_in_cents", 10000);
        tx.put("currency", "COP");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transaction", tx);

        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("checksum", checksum);
        signature.put("properties", propiedades);

        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("event", "transaction.updated");
        evento.put("data", data);
        evento.put("signature", signature);
        evento.put("timestamp", timestamp);
        return evento;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
