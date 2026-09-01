package jaider.ecommerce.tienda.envio;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Catálogo de especificaciones logísticas (peso + dimensiones reutilizables) de la tienda
 *  actual — PLAN_INTEGRACION_ENVIA.md, Fase 1. El admin las crea acá y luego las asigna a
 *  productos individuales (Producto.especificacionId). Mismo nivel de acceso que
 *  /api/v1/empaques y /api/v1/tienda/config. */
@RestController
@RequestMapping("/api/v1/especificaciones-logisticas")
@RequiredArgsConstructor
public class EspecificacionLogisticaController {

    private final EspecificacionLogisticaService service;

    @GetMapping
    public List<EspecificacionLogisticaResponse> getAll() {
        return service.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EspecificacionLogisticaResponse create(@RequestBody EspecificacionLogisticaRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    public EspecificacionLogisticaResponse update(@PathVariable Long id, @RequestBody EspecificacionLogisticaRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
