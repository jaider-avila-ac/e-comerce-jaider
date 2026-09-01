package jaider.ecommerce.tienda.envio;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cliente de la Geocodes API de Envia.com (geocodes.envia.com) — API pública, sin token, sin
 * ambiente sandbox/producción separado (a diferencia de cotizar/generar guía). Dado un código
 * postal colombiano, resuelve el nombre de ciudad y los códigos que la API de cotización real
 * de Envia SÍ acepta (state de 3 letras, código DANE de 8 dígitos) — PLAN_INTEGRACION_ENVIA.md,
 * Fase 3.
 */
@Component
public class EnviaGeocodesClient {

    private final RestClient restClient = RestClient.create();

    public GeocodeResultado resolver(String codigoPostal) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri("https://geocodes.envia.com/zipcode/CO/{cp}", codigoPostal)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo consultar el código postal " + codigoPostal + " con Envia");
        }
        // La respuesta es un arreglo (puede haber más de una coincidencia) — se usa la primera.
        if (body == null || !body.isArray() || body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El código postal " + codigoPostal + " no es válido según Envia");
        }
        JsonNode primero = body.get(0);
        String locality = primero.path("locality").asText(null);
        // No todos los departamentos tienen código de 3 letras (Bogotá D.C. solo tiene "DC" de
        // 2 — verificado en vivo: state.code.3digit viene null ahí) — se usa el de 2 letras
        // como respaldo en vez de fallar.
        JsonNode codigos = primero.path("state").path("code");
        String state3 = codigos.path("3digit").asText(null);
        if (state3 == null) state3 = codigos.path("2digit").asText(null);
        String stat8 = primero.path("info").path("stat_8digit").asText(null);
        if (locality == null || state3 == null || stat8 == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Envia devolvió una respuesta incompleta para el código postal " + codigoPostal);
        }
        return new GeocodeResultado(locality, state3, stat8);
    }
}
