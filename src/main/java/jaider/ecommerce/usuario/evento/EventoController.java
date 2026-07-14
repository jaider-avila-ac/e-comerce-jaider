package jaider.ecommerce.usuario.evento;

import jakarta.validation.Valid;
import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.catalogo.publico.PublicProductoResponse;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/eventos")
@RequiredArgsConstructor
public class EventoController {

    private final EventoService eventoService;
    private final JwtService jwtService;

    @PostMapping
    public ResponseEntity<Void> registrar(
            @Valid @RequestBody EventoRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long[] ids = extractIds(authHeader);
        eventoService.registrar(req, ids[0], ids[1]);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/recientes")
    public List<PublicProductoResponse> recientes(
            @RequestParam(defaultValue = "8") int limit,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long[] ids = extractIds(authHeader);
        return eventoService.recientes(ids[0], ids[1], limit);
    }

    @GetMapping("/categoria-favorita")
    public Map<String, Object> categoriaFavorita(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long[] ids = extractIds(authHeader);
        return eventoService.categoriaFavorita(ids[0], ids[1]);
    }

    // Devuelve [usrId, tndId] del Bearer token
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
