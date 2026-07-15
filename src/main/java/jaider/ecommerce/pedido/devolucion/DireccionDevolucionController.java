package jaider.ecommerce.pedido.devolucion;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/direcciones-devolucion")
@RequiredArgsConstructor
public class DireccionDevolucionController {

    private final DireccionDevolucionService service;

    @GetMapping
    public List<DireccionDevolucionResponse> getAll() {
        return service.getAll();
    }

    @PostMapping
    public DireccionDevolucionResponse create(@RequestBody DireccionDevolucionRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public DireccionDevolucionResponse update(@PathVariable Long id, @RequestBody DireccionDevolucionRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
