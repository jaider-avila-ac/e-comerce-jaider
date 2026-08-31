package jaider.ecommerce.auditoria;

import jaider.ecommerce.shared.dto.PageResponse;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Consulta del registro de auditoría (quién hizo qué) de la tienda actual — solo admin.
 *  SUPERADMIN nunca llega hasta acá: SecurityConfig ya lo excluye de todo /api/v1/** salvo
 *  /api/v1/superadmin/** y su propia cuenta (me/logout). */
@RestController
@RequestMapping("/api/v1/auditoria")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditoriaController {

    private final AuditoriaService service;

    @GetMapping
    public PageResponse<AuditoriaResponse> listar(
            @RequestParam(required = false) String entidad,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long tndId = Long.parseLong(TenantContext.get());
        return service.listar(tndId, entidad, page, size);
    }
}
