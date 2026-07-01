package jaider.ecommerce.catalogo.carrito;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public/carrito")
@RequiredArgsConstructor
public class CarritoController {

    private final CarritoService carritoService;

    @PostMapping("/validar")
    public List<ValidarItemResult> validar(@RequestBody ValidarCarritoRequest req) {
        return carritoService.validar(req.items());
    }
}
