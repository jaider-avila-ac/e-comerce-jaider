package jaider.ecommerce.pago.wompi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jaider.ecommerce.pago.dto.CobroTarjetaResultado;
import jaider.ecommerce.pago.dto.WebhookTransactionEvent;
import jaider.ecommerce.pago.dto.WompiAcceptanceTokensDto;
import jaider.ecommerce.pago.service.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Integración con Wompi (pasarela de pagos Colombia).
 *
 * Para PRUEBAS sin gastar dinero:
 *   1. Ingresa a https://dashboard.wompi.co → cambiar a modo Sandbox
 *   2. Copia las llaves pub_test_... y prv_test_... (son distintas a las de producción)
 *   3. En .env coloca WOMPI_PUBLIC_KEY=pub_test_... y WOMPI_INTEGRITY_KEY=<integrity_key_sandbox>
 *   4. Tarjeta de prueba: 4242 4242 4242 4242, vencimiento cualquier fecha futura, CVV 123
 *
 * La llave de integridad NO es la llave privada; se encuentra en el panel de Wompi
 * en Configuración → Llaves de integridad (es un valor distinto para producción y sandbox).
 */
@Slf4j
@Service
public class WompiService implements PaymentGateway {

    private static final String CHECKOUT_URL = "https://checkout.wompi.co/p/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${wompi.public-key}")
    private String publicKey;

    @Value("${wompi.private-key:}")
    private String privateKey;

    @Value("${wompi.integrity-key}")
    private String integrityKey;

    @Value("${wompi.events-key}")
    private String eventsKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ── Referencia única ──────────────────────────────────────────────────

    @Override
    public String generarReferencia(Long tndId, Long pedId) {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "CZC-" + tndId + "-" + pedId + "-" + uid;
    }

    // ── Checkout URL (Wompi hosted checkout) ─────────────────────────────

    /**
     * Construye la URL de pago de Wompi para redirigir al usuario.
     * Funciona igual para sandbox y producción; lo que cambia es la llave pública.
     */
    @Override
    public String buildCheckoutUrl(String referencia, long amountCentavos, String currency, String redirectUrl) {
        String integrity = calcularIntegrity(referencia, amountCentavos, currency);
        return CHECKOUT_URL
                + "?public-key=" + encode(publicKey)
                + "&currency=" + currency
                + "&amount-in-cents=" + amountCentavos
                + "&reference=" + encode(referencia)
                + "&signature:integrity=" + integrity
                + "&redirect-url=" + encode(redirectUrl);
    }

    // ── Firma SHA-256 ─────────────────────────────────────────────────────

    /** SHA-256(reference + amount_in_cents + currency + integrity_key) */
    public String calcularIntegrity(String referencia, long amountCentavos, String currency) {
        String input = referencia + amountCentavos + currency + integrityKey;
        return sha256(input);
    }

    // ── Verificación de webhook ───────────────────────────────────────────

    /**
     * Verifica la firma del evento webhook usando la llave de eventos.
     * Wompi envía en el payload: signature.properties (array de campos) y signature.checksum.
     * La firma es SHA-256 de los valores de esos campos (en orden) + timestamp + eventsKey.
     */
    @Override
    public boolean verificarWebhook(Map<String, Object> evento) {
        try {
            Map<?, ?> signature = (Map<?, ?>) evento.get("signature");
            if (signature == null) return false;

            String checksum = (String) signature.get("checksum");
            List<?> props = (List<?>) signature.get("properties");
            if (checksum == null || props == null) return false;

            Map<?, ?> data = (Map<?, ?>) evento.get("data");
            if (data == null) return false;

            StringBuilder sb = new StringBuilder();
            for (Object prop : props) {
                Object value = resolveNestedPath(data, prop.toString());
                if (value != null) sb.append(value);
            }
            Object timestamp = evento.get("timestamp");
            if (timestamp != null) sb.append(timestamp);
            sb.append(eventsKey);

            String computed = sha256(sb.toString());
            return checksum.equalsIgnoreCase(computed);
        } catch (Exception e) {
            log.error("Error verificando firma Wompi: {}", e.getMessage());
            return false;
        }
    }

    // ── Parseo del webhook al formato genérico ────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public WebhookTransactionEvent parsearWebhook(Map<String, Object> evento) {
        try {
            String eventType = (String) evento.get("event");
            Map<String, Object> data = (Map<String, Object>) evento.get("data");
            if (data == null) return null;
            Map<String, Object> tx = (Map<String, Object>) data.get("transaction");
            if (tx == null) return null;

            return new WebhookTransactionEvent(
                    eventType,
                    (String) tx.get("status"),
                    (String) tx.get("reference"),
                    (String) tx.get("id"),
                    (String) tx.get("payment_method_type"),
                    tx.get("amount_in_cents") instanceof Number n ? n.longValue() : null,
                    (String) tx.get("currency")
            );
        } catch (Exception e) {
            log.error("Error parseando webhook Wompi: {}", e.getMessage());
            return null;
        }
    }

    // ── Consulta directa por referencia (reconciliación) ─────────────────

    @Override
    public Optional<WebhookTransactionEvent> consultarTransaccion(String referencia) {
        String url = wompiBase() + "/transactions?reference=" + encode(referencia);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + publicKey)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[Reconciliación] Wompi retornó HTTP {} para referencia {}", resp.statusCode(), referencia);
                return Optional.empty();
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return Optional.empty();
            }

            JsonNode tx = data.get(0);
            return Optional.of(new WebhookTransactionEvent(
                    "transaction.updated",
                    tx.path("status").asText(null),
                    tx.path("reference").asText(null),
                    tx.path("id").asText(null),
                    tx.path("payment_method_type").asText(null),
                    tx.has("amount_in_cents") ? tx.path("amount_in_cents").asLong() : null,
                    tx.path("currency").asText(null)
            ));
        } catch (Exception e) {
            log.error("[Reconciliación] Error consultando Wompi para ref {}: {}", referencia, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Pago directo con tarjeta tokenizada (cobro único, sin guardar la fuente) ──

    @Override
    public WompiAcceptanceTokensDto obtenerTokensAceptacion() {
        String url = wompiBase() + "/merchants/" + encode(publicKey);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode data = root.path("data");

            String acceptTok = data.path("presigned_acceptance").path("acceptance_token").asText(null);
            String acceptLink = data.path("presigned_acceptance").path("permalink").asText(null);
            String authTok = data.path("presigned_personal_data_auth").path("acceptance_token").asText(null);
            String authLink = data.path("presigned_personal_data_auth").path("permalink").asText(null);

            return new WompiAcceptanceTokensDto(wompiBase(), publicKey, acceptTok, acceptLink, authTok, authLink);
        } catch (Exception e) {
            log.error("[Wompi] Error obteniendo tokens de aceptación: {}", e.getMessage());
            throw new RuntimeException("No se pudo obtener los tokens de aceptación de Wompi", e);
        }
    }

    @Override
    public Long crearFuentePago(String cardToken, String customerEmail,
                                 String acceptanceToken, String personalAuthToken) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalStateException("WOMPI_PRIVATE_KEY no configurada — el pago con tarjeta no está disponible");
        }
        String url = wompiBase() + "/payment_sources";
        String body = MAPPER.createObjectNode()
                .put("type", "CARD")
                .put("token", cardToken)
                .put("customer_email", customerEmail)
                .put("acceptance_token", acceptanceToken)
                .put("accept_personal_auth", personalAuthToken)
                .toString();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + privateKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(resp.body());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                log.error("[Wompi] Error creando fuente de pago HTTP {}: {}", resp.statusCode(), resp.body());
                throw new RuntimeException("Wompi rechazó la fuente de pago: " + resp.statusCode());
            }
            long psId = root.path("data").path("id").asLong(-1);
            if (psId <= 0) throw new RuntimeException("Wompi no retornó un ID de fuente de pago válido");
            log.info("[Wompi] Fuente de pago creada: id={} email={}", psId, customerEmail);
            return psId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Wompi] Error HTTP creando fuente de pago: {}", e.getMessage());
            throw new RuntimeException("Error de red al crear fuente de pago en Wompi", e);
        }
    }

    @Override
    public CobroTarjetaResultado cobrarFuentePago(long paymentSourceId, String customerEmail,
                                                   long amountCentavos, String referencia) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalStateException("WOMPI_PRIVATE_KEY no configurada");
        }
        String url = wompiBase() + "/transactions";
        String body;
        try {
            String integrity = calcularIntegrity(referencia, amountCentavos, "COP");
            var node = MAPPER.createObjectNode();
            node.put("amount_in_cents", amountCentavos);
            node.put("currency", "COP");
            node.put("customer_email", customerEmail);
            node.put("reference", referencia);
            node.put("payment_source_id", paymentSourceId);
            node.set("payment_method", MAPPER.createObjectNode().put("installments", 1));
            node.put("signature", integrity);
            body = node.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error construyendo body de cobro con tarjeta", e);
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + privateKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(resp.body());

            if (resp.statusCode() == 422 || resp.statusCode() == 400) {
                String errMsg = root.path("error").path("reason").asText(
                        root.path("error").path("type").asText("Error Wompi " + resp.statusCode()));
                log.warn("[Wompi Tarjeta] HTTP {} para ref {}: {}", resp.statusCode(), referencia, errMsg);
                boolean invalida = errMsg.toLowerCase().contains("token")
                        || errMsg.toLowerCase().contains("payment_source")
                        || errMsg.toLowerCase().contains("not found");
                return new CobroTarjetaResultado(null, "ERROR", errMsg, invalida);
            }

            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                log.error("[Wompi Tarjeta] HTTP {} inesperado para ref {}", resp.statusCode(), referencia);
                return new CobroTarjetaResultado(null, "ERROR",
                        "Error inesperado Wompi HTTP " + resp.statusCode(), false);
            }

            JsonNode tx = root.path("data");
            String txId = tx.path("id").asText(null);
            String status = tx.path("status").asText("PENDING");
            String statusMsg = tx.path("status_message").asText(null);

            log.info("[Wompi Tarjeta] Cobro ref={} txId={} status={} msg={}", referencia, txId, status, statusMsg);
            return new CobroTarjetaResultado(txId, status, statusMsg, false);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Wompi Tarjeta] Error de red cobrando ref {}: {}", referencia, e.getMessage());
            throw new RuntimeException("Error de red al intentar el cobro con tarjeta", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String wompiBase() {
        return (publicKey != null && publicKey.startsWith("pub_test_"))
                ? "https://sandbox.wompi.co/v1"
                : "https://production.wompi.co/v1";
    }

    private Object resolveNestedPath(Map<?, ?> root, String dotPath) {
        Object current = root;
        for (String key : dotPath.split("\\.")) {
            if (current instanceof Map<?, ?> m) current = m.get(key);
            else return null;
        }
        return current;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 error", e);
        }
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
