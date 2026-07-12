package jaider.ecommerce.infra.media;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/public/media")
@RequiredArgsConstructor
public class MediaProxyController {

    private final MediaProxyService service;

    @GetMapping("/producto/{tndId}/{imgId}")
    public ResponseEntity<byte[]> productoImagen(@PathVariable Long tndId, @PathVariable Long imgId) {
        MediaProxyService.ProxiedImage img = service.fetchProductoImagen(tndId, imgId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(img.bytes());
    }
}
