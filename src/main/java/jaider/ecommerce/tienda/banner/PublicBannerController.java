package jaider.ecommerce.tienda.banner;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public/banners")
@RequiredArgsConstructor
public class PublicBannerController {

    private final BannerService service;

    @GetMapping
    public List<BannerResponse> getActivos(@RequestParam String posicion) {
        return service.getActivosByPosicion(posicion);
    }
}
