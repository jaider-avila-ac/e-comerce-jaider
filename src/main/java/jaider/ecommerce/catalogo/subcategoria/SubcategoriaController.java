package jaider.ecommerce.catalogo.subcategoria;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/subcategorias")
@RequiredArgsConstructor
public class SubcategoriaController {

    private final SubcategoriaService service;

    @GetMapping
    public List<SubcategoriaResponse> getAll(@RequestParam(required = false) Long catId) {
        return catId != null ? service.getByCat(catId) : service.getAll();
    }

    @GetMapping("/{id}")
    public SubcategoriaResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubcategoriaResponse create(@RequestBody SubcategoriaRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public SubcategoriaResponse update(@PathVariable Long id, @RequestBody SubcategoriaRequest req) {
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
