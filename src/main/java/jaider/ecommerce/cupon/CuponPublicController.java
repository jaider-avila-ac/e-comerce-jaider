package jaider.ecommerce.cupon;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.pedido.PedidoCreacionService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Validación "en vivo" de un cupón desde el carrito — PENDIENTE_DUO_CHIC. Nunca consume un uso
 *  (eso solo pasa en el checkout real, ver CuponService.canjear vía PedidoCreacionService). */
@RestController
@RequestMapping("/api/v1/public/cupones")
@RequiredArgsConstructor
public class CuponPublicController {

    private final CuponService cuponService;
    private final PedidoCreacionService pedidoCreacionService;
    private final JwtService jwtService;

    @PostMapping("/validar")
    public ValidarCuponResponse validar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ValidarCuponRequest req) {
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

        long subtotal = pedidoCreacionService.subtotalCarritoActual(usrId);
        return cuponService.validar(tndId, req.codigo(), subtotal);
    }

    public record ValidarCuponRequest(String codigo) {}
}
