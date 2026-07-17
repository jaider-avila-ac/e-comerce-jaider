package jaider.ecommerce.catalogo.coleccion;

import jaider.ecommerce.catalogo.publico.PublicCatalogService;
import jaider.ecommerce.catalogo.publico.PublicProductoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public/colecciones")
@RequiredArgsConstructor
public class PublicColeccionController {

    private final ColeccionService service;
    private final PublicCatalogService publicCatalogService;

    @GetMapping
    public List<ColeccionResponse> getActivas() {
        return service.getAll().stream()
                .filter(ColeccionResponse::activo)
                .toList();
    }

    @GetMapping("/{id}")
    public ColeccionResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }

    /** Productos que pertenecen a esta colección — reusa el mismo armado de respuesta
     *  pública que "vistos recientemente" (getProductosByIds), sin duplicar lógica. */
    @GetMapping("/{id}/productos")
    public List<PublicProductoResponse> getProductos(@PathVariable Long id) {
        List<Long> productoIds = service.getProductoIds(id);
        return publicCatalogService.getProductosByIds(productoIds);
    }
}
