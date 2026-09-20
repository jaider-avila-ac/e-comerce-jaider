package jaider.ecommerce.tienda.envio;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Catálogo de empaques (cajas) de la tienda actual — PLAN_INTEGRACION_ENVIA.md, Fase 1.
 *  Consultarlo es igual que /api/v1/tienda/config (cualquier staff autenticado de esta tienda
 *  per SecurityConfig) — pero crear/editar/eliminar afecta directamente cómo se cotiza y cobra
 *  el envío real, así que queda igual de restringido que las acciones administrativas de
 *  PedidoController (corregir-estado/cancelar/asignar): solo ADMIN (corrección de auditoría,
 *  2026-09-01 — antes cualquier COLABORADOR/BODEGA podía alterarlo). */
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
    @PreAuthorize("hasRole('ADMIN')")
    public EmpaqueResponse create(@RequestBody EmpaqueRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EmpaqueResponse update(@PathVariable Long id, @RequestBody EmpaqueRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
