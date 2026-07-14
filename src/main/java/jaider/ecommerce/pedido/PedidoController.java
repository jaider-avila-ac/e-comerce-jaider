package jaider.ecommerce.pedido;

import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pedidos")
@RequiredArgsConstructor
public class PedidoController {

    private final PedidoService pedidoService;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    public List<PedidoResponse> getAll(@RequestParam(required = false) String estado) {
        return pedidoService.getAll(estado);
    }

    @GetMapping("/conteos")
    public java.util.Map<String, Long> conteos() {
        return pedidoService.conteosPorEstado();
    }

    @GetMapping("/{id}")
    public PedidoResponse getById(@PathVariable Long id) {
        return pedidoService.getById(id);
    }

    @PatchMapping("/{id}/estado")
    @Transactional
    public PedidoResponse updateEstado(@AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id, @RequestBody EstadoRequest req) {
        Long adminId = resolverAdminId(userDetails);
        return pedidoService.updateEstado(id, req.estado(), adminId);
    }

    /** null si el principal no corresponde a ningún admin_user activo (no debería pasar, pero
     *  se evita romper el cambio de estado por un problema de resolución de identidad). */
    private Long resolverAdminId(UserDetails userDetails) {
        if (userDetails == null) return null;
        tenantSupport.applyTenant(em);
        return adminUserRepository.findByEmail(userDetails.getUsername())
                .map(a -> a.getId())
                .orElse(null);
    }

    @PostMapping("/{id}/resolver-alerta-stock")
    public PedidoResponse resolverAlertaStock(@PathVariable Long id) {
        return pedidoService.resolverAlertaStock(id);
    }

    @PatchMapping("/{id}/link-seguimiento")
    public PedidoResponse updateSeguimiento(@PathVariable Long id, @RequestBody SeguimientoRequest req) {
        return pedidoService.updateSeguimiento(id, req.transportadora(), req.codigoRastreo(), req.link(), req.mostrar());
    }
}
