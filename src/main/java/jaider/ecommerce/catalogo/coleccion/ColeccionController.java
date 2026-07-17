package jaider.ecommerce.catalogo.coleccion;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/colecciones")
@RequiredArgsConstructor
public class ColeccionController {

    private final ColeccionService service;

    @GetMapping
    public List<ColeccionResponse> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public ColeccionResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ColeccionResponse create(@RequestBody ColeccionRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public ColeccionResponse update(@PathVariable Long id, @RequestBody ColeccionRequest req) {
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
