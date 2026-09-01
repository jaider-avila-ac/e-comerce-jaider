package jaider.ecommerce.tienda.envio;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Orden de preferencia de transportadoras de la tienda actual para cotizar con Envia.com —
 *  PLAN_INTEGRACION_ENVIA.md, Fase 3. Mismo nivel de acceso que /api/v1/empaques (cualquier
 *  staff autenticado de esta tienda per SecurityConfig, sin @PreAuthorize propio). */
@RestController
@RequestMapping("/api/v1/transportadoras")
@RequiredArgsConstructor
public class TransportadoraController {

    private final TransportadoraService service;

    @GetMapping
    public List<TransportadoraResponse> getAll() {
        return service.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransportadoraResponse create(@RequestBody TransportadoraRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    public TransportadoraResponse update(@PathVariable Long id, @RequestBody TransportadoraRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
