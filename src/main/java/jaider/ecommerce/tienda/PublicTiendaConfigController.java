package jaider.ecommerce.tienda;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/tienda/config")
@RequiredArgsConstructor
public class PublicTiendaConfigController {

    private final TiendaConfigService service;

    @GetMapping
    public TiendaConfigResponse get() {
        return service.getConfig();
    }
}
