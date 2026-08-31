package jaider.ecommerce.auth.admin;

import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Gestión de colaboradores (admin_users) de la tienda actual — solo para admin/superadmin. */
@RestController
@RequestMapping("/api/v1/admin-users")
@RequiredArgsConstructor
// SUPERADMIN nunca llega hasta acá: SecurityConfig ya lo excluye de TODO /api/v1/** salvo
// /api/v1/superadmin/** y su propia cuenta (me/logout) — ver el comentario ahí.
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService service;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    @Transactional(readOnly = true)
    public List<AdminUserResponse> listar() {
        return service.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AdminUserResponse crear(@AuthenticationPrincipal UserDetails userDetails, @RequestBody AdminUserRequest req) {
        return service.crear(actor(userDetails), req);
    }

    @PatchMapping("/{id}/activo")
    @Transactional
    public AdminUserResponse cambiarActivo(@AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return service.cambiarActivo(actor(userDetails), id, Boolean.TRUE.equals(body.get("activo")));
    }

    @PatchMapping("/{id}")
    @Transactional
    public AdminUserResponse actualizar(@AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id, @RequestBody AdminUserUpdateRequest req) {
        return service.actualizar(actor(userDetails), id, req);
    }

    private AdminUser actor(UserDetails userDetails) {
        tenantSupport.requireTenant(em);
        return adminUserRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }
}
