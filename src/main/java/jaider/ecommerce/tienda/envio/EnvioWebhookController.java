package jaider.ecommerce.tienda.envio;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Webhook público de seguimiento de Envia.com — PLAN_INTEGRACION_ENVIA.md, Fase 5. Sin JWT (Envia
 * no tiene sesión de nuestra app) — la URL lleva el {@code tndId} y, opcionalmente, un Bearer
 * token propio de esa tienda verifica la autenticidad (ver EnvioWebhookService). Siempre
 * responde 200: un webhook nunca debe reintentarse por un evento que decidimos ignorar a
 * propósito (tracking desconocido, estado no accionable, etc.) — solo un fallo real de
 * autenticación se rechaza distinto.
 */
@RestController
@RequestMapping("/api/v1/public/envios/webhook/envia")
@RequiredArgsConstructor
public class EnvioWebhookController {

    private final EnvioWebhookService service;

    @PostMapping("/{tndId}")
    public ResponseEntity<Void> recibir(@PathVariable Long tndId, @RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "Authorization", required = false) String authHeader) {
        service.procesar(tndId, body, authHeader);
        return ResponseEntity.ok().build();
    }
}
