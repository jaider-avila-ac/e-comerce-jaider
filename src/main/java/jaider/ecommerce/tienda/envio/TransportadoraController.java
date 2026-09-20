package jaider.ecommerce.tienda.envio;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Orden de preferencia de transportadoras de la tienda actual para cotizar con Envia.com —
 *  PLAN_INTEGRACION_ENVIA.md, Fase 3. Consultarlo es igual que /api/v1/empaques (cualquier
 *  staff autenticado), pero crear/editar/eliminar solo ADMIN — mismo criterio que
 *  {@link EmpaqueController} (corrección de auditoría, 2026-09-01). */
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
    @PreAuthorize("hasRole('ADMIN')")
    public TransportadoraResponse create(@RequestBody TransportadoraRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TransportadoraResponse update(@PathVariable Long id, @RequestBody TransportadoraRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
