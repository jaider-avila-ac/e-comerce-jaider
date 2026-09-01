package jaider.ecommerce.tienda;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Consultar la config es igual para todo staff autenticado (GET), pero escribirla afecta cosas
 *  como el modo de envío (incluido activar/desactivar Envia y sandbox/producción), el costo de
 *  respaldo y envío gratis — solo ADMIN (corrección de auditoría, 2026-09-01, mismo criterio que
 *  EmpaqueController/TransportadoraController/SucursalController/PedidoEnvioController). */
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
    @PreAuthorize("hasRole('ADMIN')")
    public TiendaConfigResponse update(@RequestBody TiendaConfigRequest req) {
        return service.updateConfig(req);
    }
}
