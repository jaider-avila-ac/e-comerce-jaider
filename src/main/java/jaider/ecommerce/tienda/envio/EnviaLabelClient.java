package jaider.ecommerce.tienda.envio;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de {@code POST /ship/generate/} de Envia.com — PLAN_INTEGRACION_ENVIA.md, Fase 4.
 * A DIFERENCIA de {@link EnviaRateClient} (cotizar, siempre gratis), esto crea un envío REAL y
 * cobra de la cuenta de la tienda en Envia — nunca se llama sin que el admin lo confirme
 * explícitamente desde "preparar envío", y {@link EnvioGuiaService} lo protege contra doble
 * clic (un pedido con guía ya generada no puede volver a generar otra).
 *
 * Mismos hosts que /ship/rate/ (api.envia.com / api-test.envia.com) — documentación real
 * verificada: docs.envia.com/reference/create-shipping-label.
 */
@Slf4j
@Component
public class EnviaLabelClient {

    private final RestClient restClient = EnviaPayloadHelper.clienteConTimeout();

    /** Lanza si Envia rechaza la generación — a diferencia de cotizar, acá NO se debe tragar el
     *  error silenciosamente: si algo sale mal, el admin tiene que enterarse, no debe pensar que
     *  ya tiene una guía cuando no la tiene. */
    public GuiaGenerada generar(String host, String apiToken, String carrier, String servicio,
                                DireccionEnvia origen, GeocodeResultado origenGeo,
                                DireccionEnvia destino, GeocodeResultado destinoGeo,
                                List<PaqueteCalculado> paquetes, long declaredValueCop,
                                String orderReference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", direccionPayload(origen, origenGeo, carrier));
        body.put("destination", direccionPayload(destino, destinoGeo, carrier));
        body.put("packages", EnviaPayloadHelper.paquetesPayload(paquetes, declaredValueCop));
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("type", 1);
        shipment.put("carrier", carrier);
        shipment.put("service", servicio);
        shipment.put("orderReference", orderReference);
        body.put("shipment", shipment);
        body.put("settings", Map.of("currency", "COP", "printFormat", "PDF", "printSize", "PAPER_4X6"));

        JsonNode respuesta = restClient.post()
                .uri(host + "/ship/generate/")
                .header("Authorization", "Bearer " + apiToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (respuesta == null || !"generate".equals(respuesta.path("meta").asText())) {
            String detalle = respuesta != null ? respuesta.path("error").path("message").asText("respuesta inesperada") : "sin respuesta";
            throw new IllegalStateException("Envia no generó la guía: " + detalle);
        }
        JsonNode data = respuesta.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("Envia respondió sin datos de guía");
        }
        JsonNode primero = data.get(0);

        // Corrección de auditoría (2026-09-01, tercera vuelta): antes se aceptaba CUALQUIER
        // respuesta con meta="generate" y un array data no vacío, incluso con shipmentId/
        // trackingNumber/label vacíos o totalPrice en 0 — eso dejaba un pedido marcado como "con
        // guía real" sin ningún dato usable (ni para rastrear, ni para descargar el PDF, ni el
        // costo real cobrado). Ahora se valida cada campo obligatorio antes de devolver nada —
        // si Envia mandó una respuesta incompleta, se trata como un fallo real (mismo camino que
        // cualquier otro error: EnvioGuiaService libera la reserva y el admin puede reintentar).
        String shipmentId = primero.path("shipmentId").asText("");
        String trackingNumber = primero.path("trackingNumber").asText("");
        String label = primero.path("label").asText("");
        long totalPrice = primero.path("totalPrice").asLong(0);
        if (shipmentId.isBlank() || trackingNumber.isBlank() || label.isBlank() || totalPrice <= 0) {
            throw new IllegalStateException(
                    "Envia respondió una guía incompleta (shipmentId=\"" + shipmentId + "\" trackingNumber=\""
                            + trackingNumber + "\" label=\"" + label + "\" totalPrice=" + totalPrice + ")");
        }

        return new GuiaGenerada(
                primero.path("carrier").asText(carrier),
                primero.path("service").asText(servicio),
                shipmentId,
                trackingNumber,
                primero.path("trackUrl").asText(""),
                label,
                totalPrice
        );
    }

    private Map<String, Object> direccionPayload(DireccionEnvia dir, GeocodeResultado geo, String carrier) {
        String city = "servientrega".equals(carrier) ? geo.stat8Digit() : geo.locality();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", dir.nombre());
        m.put("phone", dir.telefono());
        m.put("street", dir.calle());
        // A diferencia de /ship/rate/, /ship/generate/ SÍ exige "number" por separado (verificado
        // en vivo: "Required property missing: number") — nuestro esquema no separa calle/número
        // en dos campos, así que se manda la misma dirección completa acá también.
        m.put("number", dir.calle());
        m.put("city", city);
        m.put("state", geo.state3());
        m.put("country", "CO");
        m.put("postalCode", dir.codigoPostal());
        return m;
    }

}
