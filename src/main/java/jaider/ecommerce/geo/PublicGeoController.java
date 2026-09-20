package jaider.ecommerce.geo;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Catálogo público de departamentos/municipios de Colombia — sin tenant, sin auth (es
 * información geográfica, no de negocio). Cualquier frontend (tienda, admin, futuras tiendas)
 * lo consume en vez de mantener su propia copia. Ver {@link ColombiaGeoService}.
 */
@RestController
@RequestMapping("/api/v1/public/geo")
@RequiredArgsConstructor
public class PublicGeoController {

    private final ColombiaGeoService geoService;

    @GetMapping("/colombia")
    public Map<String, List<String>> colombia() {
        return geoService.catalogo();
    }
}
