package jaider.ecommerce.pedido;

import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/ventas-locales")
@RequiredArgsConstructor
public class VentaLocalController {

    private final VentaLocalService service;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public VentaLocalService.VentaLocalCreada crear(
            @AuthenticationPrincipal UserDetails userDetails, @RequestBody VentaLocalRequest req) {
        tenantSupport.applyTenant(em);
        Long adminId = adminUserRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"))
                .getId();
        Long tndId = Long.parseLong(TenantContext.get());
        return service.crear(tndId, adminId, req);
    }
}
