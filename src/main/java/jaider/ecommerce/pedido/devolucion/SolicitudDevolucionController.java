package jaider.ecommerce.pedido.devolucion;

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
@RequestMapping("/api/v1/devoluciones")
@RequiredArgsConstructor
public class SolicitudDevolucionController {

    private final SolicitudDevolucionService service;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    public List<SolicitudDevolucionResponse> getAll(@RequestParam(required = false) String estado) {
        return service.getAll(estado);
    }

    @GetMapping("/{id}")
    public SolicitudDevolucionResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }

    // @Transactional en estos tres métodos (no en getAll/getById, que no lo necesitan): sin
    // transacción abierta en el controller, el SET LOCAL de resolverAdminId() (tenantSupport.
    // applyTenant, dentro de una transacción propia que hace commit/rollback al salir) no
    // sobrevive hasta la consulta JPA subsiguiente — el admin_id resuelto siempre volvía null y
    // ninguna acción de devolución quedaba atribuida en la auditoría. Mismo patrón ya usado en
    // PedidoController (updateEstado/corregirEstado/cancelar/asignarme/asignar).
    @PatchMapping("/{id}/aprobar")
    @Transactional
    public SolicitudDevolucionResponse aprobar(@AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id, @RequestBody AprobarDevolucionRequest req) {
        return service.aprobar(id, req.direccionId(), req.nota(), resolverAdminId(userDetails));
    }

    @PatchMapping("/{id}/rechazar")
    @Transactional
    public SolicitudDevolucionResponse rechazar(@AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id, @RequestBody RechazarDevolucionRequest req) {
        return service.rechazar(id, req.nota(), resolverAdminId(userDetails));
    }

    @PostMapping("/{id}/confirmar-recibida")
    @Transactional
    public SolicitudDevolucionResponse confirmarRecibida(@AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return service.confirmarRecibida(id, resolverAdminId(userDetails));
    }

    /** null si el principal no corresponde a ningún admin_user activo (no debería pasar, pero
     *  se evita romper la acción por un problema de resolución de identidad). */
    private Long resolverAdminId(UserDetails userDetails) {
        if (userDetails == null) return null;
        tenantSupport.applyTenant(em);
        return adminUserRepository.findByEmail(userDetails.getUsername())
                .map(a -> a.getId())
                .orElse(null);
    }
}
