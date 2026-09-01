package jaider.ecommerce.tienda.envio;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente del endpoint real de cotización de Envia.com ({@code POST /ship/rate/}) —
 * PLAN_INTEGRACION_ENVIA.md, Fase 3. Host de producción/sandbox distintos (a diferencia de la
 * Geocodes API): {@code api.envia.com} / {@code api-test.envia.com}, confirmado en vivo.
 *
 * El campo {@code city} de origen/destino se arma DISTINTO según el carrier: la mayoría
 * (Coordinadora, InterRapidísimo) acepta el nombre real de la ciudad, pero Servientrega exige
 * el código DANE de 8 dígitos ahí — verificado empíricamente (con el nombre falla con "No se ha
 * encontrado el Codigo DANE de la Ciudad"), ver {@link GeocodeResultado}.
 */
@Slf4j
@Component
public class EnviaRateClient {

    private static final String HOST_PRODUCCION = "https://api.envia.com";
    private static final String HOST_SANDBOX = "https://api-test.envia.com";

    private final RestClient restClient = RestClient.create();

    public String hostPara(String ambiente) {
        return "produccion".equals(ambiente) ? HOST_PRODUCCION : HOST_SANDBOX;
    }

    /** Nunca lanza por un carrier individual que falle — devuelve empty para que el llamador
     *  siga con el siguiente carrier de la lista (garantía de "precio sí o sí" al cliente). */
    public Optional<CotizacionCarrier> cotizar(String host, String apiToken, String carrier,
                                                DireccionEnvia origen, GeocodeResultado origenGeo,
                                                DireccionEnvia destino, GeocodeResultado destinoGeo,
                                                List<PaqueteCalculado> paquetes, long declaredValueCop) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("origin", direccionPayload(origen, origenGeo, carrier));
            body.put("destination", direccionPayload(destino, destinoGeo, carrier));
            body.put("packages", paquetesPayload(paquetes, declaredValueCop));
            body.put("shipment", Map.of("type", 1, "carrier", carrier));

            JsonNode respuesta = restClient.post()
                    .uri(host + "/ship/rate/")
                    .header("Authorization", "Bearer " + apiToken)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (respuesta == null || !"rate".equals(respuesta.path("meta").asText())) {
                return Optional.empty();
            }
            JsonNode data = respuesta.path("data");
            if (!data.isArray() || data.isEmpty()) return Optional.empty();

            // La más barata entre los servicios que ofrezca este carrier (ej. Servientrega
            // Premier vs. otro servicio) — el cliente solo necesita UN precio, el mejor.
            JsonNode mejor = null;
            long mejorPrecio = Long.MAX_VALUE;
            for (JsonNode item : data) {
                long precio = item.path("totalPrice").asLong(Long.MAX_VALUE);
                if (precio < mejorPrecio) {
                    mejorPrecio = precio;
                    mejor = item;
                }
            }
            if (mejor == null) return Optional.empty();

            return Optional.of(new CotizacionCarrier(
                    mejor.path("carrier").asText(carrier),
                    mejor.path("service").asText(""),
                    mejor.path("serviceDescription").asText(mejor.path("service").asText("")),
                    mejorPrecio,
                    mejor.path("deliveryEstimate").asText("")
            ));
        } catch (Exception e) {
            log.warn("[EnviaRate] carrier={} no cotizó: {}", carrier, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> direccionPayload(DireccionEnvia dir, GeocodeResultado geo, String carrier) {
        // Servientrega exige el código DANE de 8 dígitos como "city"; las demás transportadoras
        // usan el nombre real de la ciudad que devuelve Geocodes (no el que escribió el cliente).
        String city = "servientrega".equals(carrier) ? geo.stat8Digit() : geo.locality();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", dir.nombre());
        m.put("phone", dir.telefono());
        m.put("street", dir.calle());
        m.put("city", city);
        m.put("state", geo.state3());
        m.put("country", "CO");
        m.put("postalCode", dir.codigoPostal());
        return m;
    }

    private List<Map<String, Object>> paquetesPayload(List<PaqueteCalculado> paquetes, long declaredValueCop) {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (PaqueteCalculado p : paquetes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("content", p.empaqueNombre());
            m.put("amount", p.cantidad());
            m.put("type", "box");
            m.put("weight", Math.max(1, p.pesoGramosPorUnidad() / 1000.0));
            m.put("weightUnit", "KG");
            m.put("lengthUnit", "CM");
            m.put("dimensions", Map.of("length", p.largoCm(), "width", p.anchoCm(), "height", p.altoCm()));
            // Simplificación: el valor declarado total del carrito se repite en cada renglón —
            // suficiente para cotizar (esto NO genera ninguna guía real, solo un precio
            // estimado); afinarlo por renglón es tarea de cuando se genere la guía de verdad.
            m.put("declaredValue", declaredValueCop);
            lista.add(m);
        }
        return lista;
    }
}
