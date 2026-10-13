package jaider.ecommerce.catalogo.publico;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SitemapController {

    private final SitemapService service;

    @GetMapping(value = "/api/v1/public/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap(HttpServletRequest request) {
        return service.generar(hostReal(request));
    }

    /** Mismo patrón que TenantInterceptor.hostReal() / UsuarioAuthController.clientIp(): detrás
     *  del proxy de Coolify, el Host de conexión real siempre es el del proxy — hay que leer
     *  X-Forwarded-Host cuando está presente. */
    private String hostReal(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-Host");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getHeader("Host");
    }
}
