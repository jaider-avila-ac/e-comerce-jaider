package jaider.ecommerce.usuario.cliente;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/clientes/me")
@RequiredArgsConstructor
public class TiendaClientePerfilController {

    private final TiendaClientePerfilService service;
    private final JwtService jwtService;

    @GetMapping
    public Map<String, Object> getPerfil(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long[] ids = extractIds(authHeader);
        return service.getPerfil(ids[0], ids[1]);
    }

    @PutMapping
    public Map<String, Object> updatePerfil(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ClientePerfilRequest req) {
        Long[] ids = extractIds(authHeader);
        return service.updatePerfil(ids[0], ids[1], req);
    }

    @PostMapping("/direcciones")
    public List<Map<String, Object>> addDireccion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ClienteDireccionRequest req) {
        Long[] ids = extractIds(authHeader);
        return service.addDireccion(ids[0], ids[1], req);
    }

    @PutMapping("/direcciones/{id}")
    public List<Map<String, Object>> updateDireccion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody ClienteDireccionRequest req) {
        Long[] ids = extractIds(authHeader);
        return service.updateDireccion(ids[0], ids[1], id, req);
    }

    @DeleteMapping("/direcciones/{id}")
    public ResponseEntity<List<Map<String, Object>>> deleteDireccion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        Long[] ids = extractIds(authHeader);
        return ResponseEntity.ok(service.deleteDireccion(ids[0], ids[1], id));
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token sin usuario");
        }
        TenantContext.set(tndId.toString());
        return new Long[]{usrId, tndId};
    }
}
