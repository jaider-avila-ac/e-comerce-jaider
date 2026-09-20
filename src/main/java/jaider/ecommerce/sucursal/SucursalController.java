package jaider.ecommerce.sucursal;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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

    // Listado liviano para selectores (alta de colaborador, filtros) — cualquier staff
    // autenticado lo necesita, se queda abierto.
    @GetMapping
    public List<SucursalResponse> listar() {
        return service.listar();
    }

    // Crear una sucursal nueva es exclusivo del superadmin (decisión explícita del usuario,
    // 2026-09-01) — a propósito no hay POST acá. Este PUT solo edita una sucursal existente, y
    // afecta directamente el origen usado para cotizar/cobrar envío real — solo ADMIN (corrección
    // de auditoría, 2026-09-01, mismo criterio que EmpaqueController/TransportadoraController).
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SucursalResponse actualizar(@PathVariable Long id, @RequestBody SucursalUpdateRequest req) {
        return service.actualizar(id, req);
    }
}
