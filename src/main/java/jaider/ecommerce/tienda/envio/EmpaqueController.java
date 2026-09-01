package jaider.ecommerce.tienda.envio;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Catálogo de empaques (cajas) de la tienda actual — PLAN_INTEGRACION_ENVIA.md, Fase 1.
 *  Mismo nivel de acceso que /api/v1/tienda/config (sin @PreAuthorize propio, cualquier staff
 *  autenticado de esta tienda per SecurityConfig). */
@RestController
@RequestMapping("/api/v1/empaques")
@RequiredArgsConstructor
public class EmpaqueController {

    private final EmpaqueService service;

    @GetMapping
    public List<EmpaqueResponse> getAll() {
        return service.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmpaqueResponse create(@RequestBody EmpaqueRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    public EmpaqueResponse update(@PathVariable Long id, @RequestBody EmpaqueRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
