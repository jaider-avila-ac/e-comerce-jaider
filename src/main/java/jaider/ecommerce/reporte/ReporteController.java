package jaider.ecommerce.reporte;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reportes")
@RequiredArgsConstructor
public class ReporteController {

    private final ReporteService service;

    // El resumen lo usa tanto el Dashboard (todo el staff) como Reportes (solo admin) — en vez
    // de bloquear todo el endpoint, el propio servicio redacta las cifras de ingresos cuando
    // quien llama no es admin/superadmin (ver ReporteService.resumen).
    @GetMapping("/resumen")
    public ReporteResumenResponse resumen(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId,
            Authentication auth) {
        return service.resumen(mes, esAdmin(auth), colaboradorId, sucursalId);
    }

    // Igual que resumen(): lo usa tanto el Dashboard (todo el staff) como Reportes (solo admin),
    // así que no se bloquea el endpoint completo — el servicio redacta el campo "total" por rol.
    @GetMapping("/pedidos-por-estado")
    public List<Map<String, Object>> pedidosPorEstado(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId,
            Authentication auth) {
        return service.pedidosPorEstado(mes, esAdmin(auth), colaboradorId, sucursalId);
    }

    @GetMapping("/productos-mas-vendidos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    public List<Map<String, Object>> productosMasVendidos(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId) {
        return service.productosMasVendidos(mes, colaboradorId, sucursalId);
    }

    @GetMapping("/ventas-por-categoria")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    public List<Map<String, Object>> ventasPorCategoria(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId) {
        return service.ventasPorCategoria(mes, colaboradorId, sucursalId);
    }

    private boolean esAdmin(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPERADMIN"));
    }
}
