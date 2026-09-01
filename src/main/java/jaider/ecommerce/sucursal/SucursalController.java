package jaider.ecommerce.sucursal;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sucursales")
@RequiredArgsConstructor
public class SucursalController {

    private final SucursalService service;

    @GetMapping
    public List<SucursalResponse> listar() {
        return service.listar();
    }

    // Crear una sucursal nueva es exclusivo del superadmin (decisión explícita del usuario,
    // 2026-09-01) — a propósito no hay POST acá. Este PUT solo edita una sucursal existente.
    @PutMapping("/{id}")
    public SucursalResponse actualizar(@PathVariable Long id, @RequestBody SucursalUpdateRequest req) {
        return service.actualizar(id, req);
    }
}
