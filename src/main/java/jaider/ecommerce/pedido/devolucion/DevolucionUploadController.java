package jaider.ecommerce.pedido.devolucion;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.infra.CloudinaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/**
 * Subida de fotos de evidencia para una solicitud de devolución — pública (requiere el JWT
 * del propio cliente, no un rol de admin), a diferencia de UploadController que es solo admin.
 */
@RestController
@RequestMapping("/api/v1/public/upload")
@RequiredArgsConstructor
public class DevolucionUploadController {

    private final CloudinaryService cloudinaryService;
    private final JwtService jwtService;

    @PostMapping("/devolucion")
    public Map<String, String> uploadFotoDevolucion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file") MultipartFile file,
            @RequestParam("numeroPedido") String numeroPedido) {
        Long[] ids = extractIds(authHeader);
        try {
            String url = cloudinaryService.uploadDevolucion(file, ids[1], numeroPedido);
            return Map.of("url", url);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al subir la foto");
        }
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
        return new Long[]{usrId, tndId};
    }
}
