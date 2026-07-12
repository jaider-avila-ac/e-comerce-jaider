package jaider.ecommerce.pago;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Webhook de Wompi — vive bajo /api/v1/public para quedar fuera de la autenticación JWT
 * (Wompi llama a este endpoint directamente, sin token). La autenticidad se valida por firma
 * (ver PagoWebhookService.procesarWebhook → PaymentGateway.verificarWebhook).
 */
@RestController
@RequestMapping("/api/v1/public/pagos")
@RequiredArgsConstructor
public class PagoWebhookController {

    private final PagoWebhookService webhookService;

    @PostMapping("/webhook/wompi")
    public void webhook(@RequestBody Map<String, Object> evento) {
        webhookService.procesarWebhook(evento);
    }
}
