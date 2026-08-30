package jaider.ecommerce.pago;

import jaider.ecommerce.pago.dto.WompiAcceptanceTokensDto;
import jaider.ecommerce.pago.wompi.WompiGatewayFactory;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Endpoints públicos de pagos que no requieren sesión (datos del merchant Wompi). */
@RestController
@RequestMapping("/api/v1/public/pagos")
@RequiredArgsConstructor
public class PagoController {

    private final WompiGatewayFactory gatewayFactory;

    /** Tokens de aceptación que el frontend debe mostrar antes de tokenizar la tarjeta del cliente. */
    @GetMapping("/acceptance-tokens")
    public WompiAcceptanceTokensDto acceptanceTokens() {
        String tndId = TenantContext.get();
        if (tndId == null || tndId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id requerido");
        }
        return gatewayFactory.forTenant(Long.parseLong(tndId)).obtenerTokensAceptacion();
    }
}
