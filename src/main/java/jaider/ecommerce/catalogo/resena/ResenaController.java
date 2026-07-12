package jaider.ecommerce.catalogo.resena;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/public/productos/{prdId}/resenas")
@RequiredArgsConstructor
public class ResenaController {

    private final ResenaService resenaService;
    private final ResenaCacheFacade resenaCacheFacade;
    private final JwtService jwtService;

    @GetMapping
    public ResenaListResponse listar(@PathVariable Long prdId) {
        return resenaCacheFacade.listarPublicas(prdId);
    }

    @GetMapping("/estado")
    public ResenaEstadoResponse estado(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long prdId) {
        Long[] ids = extractIds(authHeader);
        return resenaService.estadoParaUsuario(prdId, ids[0], ids[1]);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResenaResponse crear(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long prdId,
            @RequestBody ResenaRequest req) {
        Long[] ids = extractIds(authHeader);
        return resenaService.crear(prdId, ids[0], ids[1], req);
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
