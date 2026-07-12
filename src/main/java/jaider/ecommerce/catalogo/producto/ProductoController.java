package jaider.ecommerce.catalogo.producto;

import jaider.ecommerce.shared.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/productos")
@RequiredArgsConstructor
public class ProductoController {

    private final ProductoService service;

    @GetMapping
    public PageResponse<ProductoResponse> getAll(
            @RequestParam(required = false) Long catId,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(catId, activo, q, page, size);
    }

    @GetMapping("/{id}")
    public ProductoResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductoResponse create(@RequestBody ProductoRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public ProductoResponse update(@PathVariable Long id, @RequestBody ProductoRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
