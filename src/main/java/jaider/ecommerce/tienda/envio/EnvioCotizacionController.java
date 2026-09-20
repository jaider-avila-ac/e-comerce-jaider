package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Precio de envío real del carrito armado — PLAN_INTEGRACION_ENVIA.md, Fase 3. Solo para
 * tiendas en modo 'envia' (ver {@link EnvioCotizacionService}); las demás calculan su envío
 * dentro del propio checkout (contra entrega / fijo), sin necesitar este endpoint.
 */
@RestController
@RequestMapping("/api/v1/public/carrito")
@RequiredArgsConstructor
public class EnvioCotizacionController {

    private final EnvioCotizacionService service;
    private final JwtService jwtService;

    @GetMapping("/envio-cotizacion")
    public EnvioCotizacionResponse cotizar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam Long direccionId) {
        Long[] ids = extractIds(authHeader);
        return service.cotizar(ids[0], ids[1], direccionId);
    }

    private Long[] extractIds(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
        }
        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido");
        }
        Long usrId = jwtService.extractUsrId(token);
        Long tndId = jwtService.extractTndId(token);
        if (usrId == null || tndId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token sin usr_id");
        }
        TenantContext.set(tndId.toString());
        return new Long[]{usrId, tndId};
    }
}
