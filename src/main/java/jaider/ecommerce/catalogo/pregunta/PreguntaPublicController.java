package jaider.ecommerce.catalogo.pregunta;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Mismo patrón que ResenaController: el listado es público (cualquiera puede leer las
 *  preguntas de un producto sin sesión), crear/editar/borrar exige estar logueado. */
@RestController
@RequestMapping("/api/v1/public/productos/{prdId}/preguntas")
@RequiredArgsConstructor
public class PreguntaPublicController {

    private final PreguntaService preguntaService;
    private final JwtService jwtService;

    @GetMapping
    public List<PreguntaResponse> listar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long prdId) {
        Long[] ids = extractIdsOpcional(authHeader);
        return preguntaService.listarPublicas(prdId, ids != null ? ids[0] : null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PreguntaResponse crear(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long prdId,
            @RequestBody PreguntaRequest req) {
        Long[] ids = extractIds(authHeader);
        return preguntaService.crear(prdId, ids[0], ids[1], req);
    }

    @PutMapping("/{pregId}")
    public void editar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long prdId, @PathVariable Long pregId,
            @RequestBody PreguntaRequest req) {
        Long[] ids = extractIds(authHeader);
        preguntaService.editar(pregId, ids[0], ids[1], req);
    }

    @DeleteMapping("/{pregId}")
    public void eliminar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long prdId, @PathVariable Long pregId) {
        Long[] ids = extractIds(authHeader);
        preguntaService.eliminarPropia(pregId, ids[0], ids[1]);
    }

    private Long[] extractIds(String authHeader) {
        Long[] ids = extractIdsOpcional(authHeader);
        if (ids == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
        }
        return ids;
    }

    private Long[] extractIdsOpcional(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) return null;
        Long usrId = jwtService.extractUsrId(token);
        Long tndId = jwtService.extractTndId(token);
        if (usrId == null || tndId == null) return null;
        TenantContext.set(tndId.toString());
        return new Long[]{usrId, tndId};
    }
}
