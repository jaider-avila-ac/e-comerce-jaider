package jaider.ecommerce.reporte;

import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
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
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    // El resumen lo usa tanto el Dashboard (todo el staff) como Reportes (solo admin). Un
    // colaborador nunca ve cifras de otro ni de la tienda completa — el servicio ignora
    // cualquier colaboradorId que mande y lo fuerza al suyo propio (ver ReporteService.resumen),
    // así que acá solo hace falta resolver quién es. Los clientes nunca se filtran por
    // colaborador (un cliente no es "de" nadie en particular — su próximo pedido lo puede
    // gestionar cualquiera), eso queda igual para todos los roles.
    @GetMapping("/resumen")
    @Transactional
    public ReporteResumenResponse resumen(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId,
            Authentication auth) {
        boolean esAdmin = esAdmin(auth);
        return service.resumen(mes, esAdmin, esAdmin ? colaboradorId : resolverAdminId(auth), sucursalId);
    }

    // Igual que resumen(): un colaborador solo ve el desglose por estado de SUS propios pedidos.
    @GetMapping("/pedidos-por-estado")
    @Transactional
    public List<Map<String, Object>> pedidosPorEstado(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId,
            Authentication auth) {
        boolean esAdmin = esAdmin(auth);
        return service.pedidosPorEstado(mes, esAdmin, esAdmin ? colaboradorId : resolverAdminId(auth), sucursalId);
    }

    /** Solo se llama cuando quien pide el reporte NO es admin — resuelve su propio admin_user.id
     *  para que el servicio nunca pueda mostrarle datos de otro colaborador ni de la tienda
     *  completa. Null si no resuelve (no debería pasar): el servicio lo trata como "sin datos"
     *  en vez de caer, por error, en un reporte sin filtrar.
     *  Tiene que ejecutarse DENTRO de la misma transacción que el resto (por eso @Transactional
     *  quedó en el método del controller, no aquí) — set_config(..., true) de applyTenant es un
     *  SET LOCAL, solo dura la transacción actual; sin una transacción común, el tenant se perdía
     *  entre este SELECT y la consulta de resumen(), y el RLS de admin_users no encontraba a
     *  nadie (encontrado probando en vivo: un colaborador real siempre veía el reporte en cero). */
    private Long resolverAdminId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        Object principal = auth.getPrincipal();
        if (!(principal instanceof UserDetails userDetails)) return null;
        tenantSupport.requireTenant(em);
        return adminUserRepository.findByEmail(userDetails.getUsername())
                .map(a -> a.getId())
                .orElse(null);
    }

    @GetMapping("/productos-mas-vendidos")
    // SUPERADMIN nunca llega hasta acá: SecurityConfig ya lo excluye de todo /api/v1/** salvo
    // /api/v1/superadmin/** y su propia cuenta (me/logout).
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> productosMasVendidos(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId) {
        return service.productosMasVendidos(mes, colaboradorId, sucursalId);
    }

    @GetMapping("/ventas-por-categoria")
    // SUPERADMIN nunca llega hasta acá: SecurityConfig ya lo excluye de todo /api/v1/** salvo
    // /api/v1/superadmin/** y su propia cuenta (me/logout).
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> ventasPorCategoria(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId) {
        return service.ventasPorCategoria(mes, colaboradorId, sucursalId);
    }

    @GetMapping("/ventas-por-canal")
    // SUPERADMIN nunca llega hasta acá: SecurityConfig ya lo excluye de todo /api/v1/** salvo
    // /api/v1/superadmin/** y su propia cuenta (me/logout).
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> ventasPorCanal(@RequestParam(required = false) String mes,
            @RequestParam(required = false) Long colaboradorId, @RequestParam(required = false) Long sucursalId) {
        return service.ventasPorCanal(mes, colaboradorId, sucursalId);
    }

    // SUPERADMIN nunca llega hasta acá (ver arriba), así que solo ROLE_ADMIN cuenta como admin.
    private boolean esAdmin(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN"));
    }
}
