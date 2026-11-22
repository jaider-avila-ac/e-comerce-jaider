package jaider.ecommerce.cupon;

import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Cupones ("bono de compra") — PENDIENTE_DUO_CHIC. Los gestiona el propio admin de cada tienda,
 *  igual que Wompi (ver TiendaWompiController): el tenant se resuelve SIEMPRE del JWT, nunca de
 *  un parámetro de la URL. */
@RestController
@RequestMapping("/api/v1/tienda/cupones")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class CuponAdminController {

    private final CuponService service;

    @GetMapping
    public List<CuponResponse> listar() {
        return service.listar(tndIdActual());
    }

    @PostMapping
    public CuponResponse crear(@RequestBody CrearCuponRequest req, @AuthenticationPrincipal UserDetails principal) {
        return service.crear(tndIdActual(), req, principal.getUsername());
    }

    @PatchMapping("/{id}/activo")
    public CuponResponse cambiarActivo(@PathVariable Long id, @RequestBody CambiarActivoRequest req,
                                        @AuthenticationPrincipal UserDetails principal) {
        return service.cambiarActivo(tndIdActual(), id, req.activo(), principal.getUsername());
    }

    public record CambiarActivoRequest(boolean activo) {}

    private Long tndIdActual() {
        String id = TenantContext.get();
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        return Long.parseLong(id);
    }
}
