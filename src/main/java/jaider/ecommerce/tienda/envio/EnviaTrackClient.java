package jaider.ecommerce.tienda.envio;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Cliente de {@code POST /ship/generaltrack/} de Envia.com — PLAN_INTEGRACION_ENVIA.md, Fase 5.
 * Da el timeline de seguimiento en tiempo real (tipo Mercado Libre), a diferencia del webhook
 * (que solo avisa cuando cambia algo): esto es "bajo demanda", para cuando el cliente abre la
 * pantalla de seguimiento de su pedido.
 *
 * Verificado en vivo (sandbox, tracking real generado en la Fase 4): la respuesta real trae
 * {@code status}, {@code statusColor}, {@code estimatedDelivery}, {@code pickupDate},
 * {@code shippedAt}, {@code deliveredAt}, {@code trackUrl} y {@code eventHistory} (arreglo de
 * eventos — vacío en un envío de sandbox recién creado sin movimiento real, se llena cuando la
 * transportadora empieza a reportar). Se devuelve el JSON tal cual (JsonNode) en vez de forzarlo
 * a un DTO estricto: trae bastantes campos cosméticos específicos de cada carrier (colores,
 * tags de traducción) que un DTO fijo volvería frágil sin necesidad.
 */
@Component
public class EnviaTrackClient {

    private final RestClient restClient = EnviaPayloadHelper.clienteConTimeout();

    public JsonNode rastrear(String host, String apiToken, String trackingNumber) {
        return restClient.post()
                .uri(host + "/ship/generaltrack/")
                .header("Authorization", "Bearer " + apiToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("trackingNumbers", List.of(trackingNumber)))
                .retrieve()
                .body(JsonNode.class);
    }
}
