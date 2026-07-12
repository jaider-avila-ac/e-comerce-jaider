package jaider.ecommerce.catalogo.publico;

import jaider.ecommerce.shared.dto.PageResponse;
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

    // Scroll infinito del catálogo: pide páginas pequeñas en vez de todo el listado.
    @GetMapping("/productos/paginado")
    public PageResponse<PublicProductoResponse> getProductosPaginado(
            @RequestParam(required = false) Long catId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size
    ) {
        return facade.getProductosPaginado(catId, q, page, size);
    }

    @GetMapping("/productos/{id}")
    public PublicProductoResponse getProducto(@PathVariable Long id) {
        return facade.getProductoById(id);
    }
}
