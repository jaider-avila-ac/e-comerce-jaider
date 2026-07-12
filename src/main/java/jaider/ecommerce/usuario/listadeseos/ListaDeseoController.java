package jaider.ecommerce.usuario.listadeseos;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.catalogo.publico.PublicProductoResponse;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Lista de deseos del cliente autenticado en la tienda pública. */
@RestController
@RequestMapping("/api/v1/public/lista-deseos")
@RequiredArgsConstructor
public class ListaDeseoController {

    private final ListaDeseoService service;
    private final JwtService jwtService;

    @GetMapping
    public List<Long> listar(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long usrId = extractUsrId(authHeader);
        return service.listarIds(usrId);
    }

    @GetMapping("/detalle")
    public List<PublicProductoResponse> listarDetalle(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long usrId = extractUsrId(authHeader);
        return service.listarDetalle(usrId);
    }

    @PostMapping("/{prdId}")
    public void agregar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long prdId) {
        Long usrId = extractUsrId(authHeader);
        service.agregar(usrId, prdId);
    }

    @DeleteMapping("/{prdId}")
    public void quitar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long prdId) {
        Long usrId = extractUsrId(authHeader);
        service.quitar(usrId, prdId);
    }

    private Long extractUsrId(String authHeader) {
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
        return usrId;
    }
}
