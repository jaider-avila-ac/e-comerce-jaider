package jaider.ecommerce.catalogo.coleccion;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public/colecciones")
@RequiredArgsConstructor
public class PublicColeccionController {

    private final ColeccionService service;

    @GetMapping
    public List<ColeccionResponse> getActivas() {
        return service.getAll().stream()
                .filter(ColeccionResponse::activo)
                .toList();
    }

    @GetMapping("/{id}")
    public ColeccionResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }
}
