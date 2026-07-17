package jaider.ecommerce.pago.reembolso;

import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reembolsos")
@RequiredArgsConstructor
public class ReembolsoController {

    private final ReembolsoService reembolsoService;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @PatchMapping("/{id}/confirmar")
    @Transactional
    public void confirmar(@AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id, @RequestBody ConfirmarReembolsoRequest req) {
        Long adminId = resolverAdminId(userDetails);
        reembolsoService.confirmarManual(id, req.estado(), req.nota(), adminId);
    }

    private Long resolverAdminId(UserDetails userDetails) {
        if (userDetails == null) return null;
        tenantSupport.applyTenant(em);
        return adminUserRepository.findByEmail(userDetails.getUsername())
                .map(a -> a.getId())
                .orElse(null);
    }
}
