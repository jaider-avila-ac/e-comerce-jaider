package jaider.ecommerce.infra;

import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
public class UploadController {

    private final CloudinaryService cloudinaryService;

    /**
     * @param productId  ID del producto (null para productos nuevos aún sin ID)
     * @param esVideo    true → sube como video (mp4/webm); false → imagen
     */
    @PostMapping("/imagen")
    public Map<String, String> uploadArchivo(
            @RequestParam("file")                          MultipartFile file,
            @RequestParam(value = "productId",  required = false) Long    productId,
            @RequestParam(value = "esVideo",    defaultValue = "false")  boolean esVideo) {

        String tndIdStr = TenantContext.get();
        if (tndIdStr == null || tndIdStr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant no identificado");
        }
        try {
            String url = cloudinaryService.upload(file, Long.parseLong(tndIdStr), productId, esVideo);
            return Map.of("url", url);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al subir archivo");
        }
    }

    /**
     * @param esVideo true → sube como video (mp4/webm); false → imagen
     */
    @PostMapping("/categoria")
    public Map<String, String> uploadCategoria(@RequestParam("file") MultipartFile file) {
        String tndIdStr = TenantContext.get();
        if (tndIdStr == null || tndIdStr.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant no identificado");
        try {
            String url = cloudinaryService.uploadCategoria(file, Long.parseLong(tndIdStr));
            return Map.of("url", url);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al subir imagen");
        }
    }

    @PostMapping("/coleccion")
    public Map<String, String> uploadColeccion(@RequestParam("file") MultipartFile file) {
        String tndIdStr = TenantContext.get();
        if (tndIdStr == null || tndIdStr.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant no identificado");
        try {
            String url = cloudinaryService.uploadColeccion(file, Long.parseLong(tndIdStr));
            return Map.of("url", url);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al subir imagen");
        }
    }

    @PostMapping("/banner")
    public Map<String, String> uploadBanner(
            @RequestParam("file")                       MultipartFile file,
            @RequestParam(value = "esVideo", defaultValue = "false") boolean esVideo) {

        String tndIdStr = TenantContext.get();
        if (tndIdStr == null || tndIdStr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant no identificado");
        }
        try {
            String url = cloudinaryService.uploadBanner(file, Long.parseLong(tndIdStr), esVideo);
            return Map.of("url", url);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al subir archivo");
        }
    }
}
