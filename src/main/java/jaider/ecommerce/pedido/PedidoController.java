package jaider.ecommerce.pedido;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pedidos")
@RequiredArgsConstructor
public class PedidoController {

    private final PedidoService pedidoService;

    @GetMapping
    public List<PedidoResponse> getAll(@RequestParam(required = false) String estado) {
        return pedidoService.getAll(estado);
    }

    @GetMapping("/{id}")
    public PedidoResponse getById(@PathVariable Long id) {
        return pedidoService.getById(id);
    }

    @PatchMapping("/{id}/estado")
    public PedidoResponse updateEstado(@PathVariable Long id, @RequestBody EstadoRequest req) {
        return pedidoService.updateEstado(id, req.estado());
    }
}
