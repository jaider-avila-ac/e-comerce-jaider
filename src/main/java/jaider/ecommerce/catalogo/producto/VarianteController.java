package jaider.ecommerce.catalogo.producto;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/variantes")
@RequiredArgsConstructor
public class VarianteController {

    private final ProductoService service;

    @PatchMapping("/{id}/stock")
    public VarianteResponse updateStock(@PathVariable Long id, @RequestBody StockRequest req) {
        return service.updateStock(id, req.cantidad());
    }

    @GetMapping("/inventario-resumen")
    public Map<String, Object> inventarioResumen() {
        return service.inventarioResumen();
    }

    @GetMapping("/inventario")
    public Map<String, Object> inventario() {
        return service.inventario();
    }

    @GetMapping("/low-stock")
    public List<Map<String, Object>> lowStock(
            @RequestParam(defaultValue = "10") int limite) {
        return service.getLowStock(limite);
    }

    public record StockRequest(Integer cantidad) {}
}
