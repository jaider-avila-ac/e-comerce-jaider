package jaider.ecommerce.pago;

import jaider.ecommerce.pago.dto.WompiAcceptanceTokensDto;
import jaider.ecommerce.pago.service.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints públicos de pagos que no requieren sesión (datos del merchant Wompi). */
@RestController
@RequestMapping("/api/v1/public/pagos")
@RequiredArgsConstructor
public class PagoController {

    private final PaymentGateway paymentGateway;

    /** Tokens de aceptación que el frontend debe mostrar antes de tokenizar la tarjeta del cliente. */
    @GetMapping("/acceptance-tokens")
    public WompiAcceptanceTokensDto acceptanceTokens() {
        return paymentGateway.obtenerTokensAceptacion();
    }
}
