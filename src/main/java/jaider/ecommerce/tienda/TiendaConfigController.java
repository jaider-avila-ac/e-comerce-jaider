package jaider.ecommerce.tienda;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tienda/config")
@RequiredArgsConstructor
public class TiendaConfigController {

    private final TiendaConfigService service;

    @GetMapping
    public TiendaConfigResponse get() {
        return service.getConfig();
    }

    @PatchMapping
    public TiendaConfigResponse update(@RequestBody TiendaConfigRequest req) {
        return service.updateConfig(req);
    }
}
