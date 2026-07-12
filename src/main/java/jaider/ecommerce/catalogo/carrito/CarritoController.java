package jaider.ecommerce.catalogo.carrito;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/carrito")
@RequiredArgsConstructor
public class CarritoController {

    private final CarritoService carritoService;
    private final JwtService jwtService;

    @PostMapping("/validar")
    public List<ValidarItemResult> validar(@RequestBody ValidarCarritoRequest req) {
        return carritoService.validar(req.items());
    }

    @GetMapping
    public Map<String, Object> getCarrito(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long[] ids = extractIds(authHeader);
        return carritoService.getCarrito(ids[0], ids[1]);
    }

    @PostMapping("/items")
    public Map<String, Object> addItem(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CarritoItemRequest req) {
        Long[] ids = extractIds(authHeader);
        int cantidad = req.cantidad() != null ? req.cantidad() : 1;
        return carritoService.addItem(ids[0], ids[1], req.prdId(), req.talla(), req.color(), cantidad);
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody CarritoUpdateRequest req) {
        Long[] ids = extractIds(authHeader);
        int cantidad = req.cantidad() != null ? req.cantidad() : 0;
        return carritoService.updateItem(ids[0], ids[1], id, cantidad);
    }

    @DeleteMapping("/items/{id}")
    public Map<String, Object> removeItem(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        Long[] ids = extractIds(authHeader);
        return carritoService.removeItem(ids[0], ids[1], id);
    }

    @DeleteMapping
    public Map<String, Object> clear(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long[] ids = extractIds(authHeader);
        return carritoService.clear(ids[0], ids[1]);
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
