package jaider.ecommerce.catalogo.publico;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicCatalogController {

    private final PublicCatalogFacade facade;

    @GetMapping("/categorias")
    public List<PublicCategoriaResponse> getCategorias() {
        return facade.getCategorias();
    }

    @GetMapping("/productos")
    public List<PublicProductoResponse> getProductos(
            @RequestParam(required = false) Long catId,
            @RequestParam(required = false) String q
    ) {
        return facade.getProductos(catId, q);
    }

    @GetMapping("/productos/{id}")
    public PublicProductoResponse getProducto(@PathVariable Long id) {
        return facade.getProductoById(id);
    }
}
