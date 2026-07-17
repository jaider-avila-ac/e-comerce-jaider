package jaider.ecommerce.catalogo.categoria;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categorias")
@RequiredArgsConstructor
public class CategoriaController {

    private final CategoriaService service;

    @GetMapping
    public List<CategoriaResponse> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public CategoriaResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoriaResponse create(@RequestBody CategoriaRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public CategoriaResponse update(@PathVariable Long id, @RequestBody CategoriaRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PatchMapping("/reordenar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reordenar(@RequestBody List<Long> ids) {
        service.reordenar(ids);
    }
}
