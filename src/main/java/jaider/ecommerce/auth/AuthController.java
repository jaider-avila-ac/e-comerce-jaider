package jaider.ecommerce.auth;

import jaider.ecommerce.auth.admin.AdminUser;
import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.auth.dto.AdminMeResponse;
import jaider.ecommerce.auth.dto.LoginRequest;
import jaider.ecommerce.auth.dto.LoginResponse;
import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/admin")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;
    private final LoginRateLimiter rateLimiter;

    @PersistenceContext
    private EntityManager em;

    @Value("${jwt.expiration-ms:86400000}")
    private long expirationMs;

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        String identificador = "admin:" + req.email().trim().toLowerCase();
        rateLimiter.verificarLimite(identificador);
        try {
            Authentication auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email(), req.password())
            );
            UserDetails user = (UserDetails) auth.getPrincipal();

            tenantSupport.applyTenant(em);
            AdminUser admin = adminUserRepository.findByEmail(user.getUsername()).orElseThrow();
            if (admin.getTiendaId() == null) {
                // Un superadmin (tienda_id NULL por diseño, ver chk_admin_users_superadmin) NUNCA
                // debe recibir automáticamente el tenant 1 — el plan multi-tenant exige selección
                // explícita y auditada de la tienda sobre la que va a actuar (§11.2), algo que
                // todavía no existe como flujo propio. Hasta que se construya, este login rechaza
                // en vez de emitir un JWT con un tenant que el superadmin no eligió — es preferible
                // a repetir el bug de asumir "calzacaribe = 1" silenciosamente.
                rateLimiter.registrarFallo(identificador);
                throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                        "El acceso de superadministrador requiere seleccionar explícitamente una tienda — ese flujo aún no está implementado.");
            }
            String token = jwtService.generate(admin.getEmail(), admin.getRol(), admin.getTiendaId());

            rateLimiter.registrarExito(identificador);
            return ResponseEntity.ok(new LoginResponse(
                    token, expirationMs, admin.getEmail(), admin.getNombre(), admin.getTiendaId(), admin.getRol()
            ));

        } catch (AuthenticationException e) {
            rateLimiter.registrarFallo(identificador);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Credenciales incorrectas"));
        }
    }

    @GetMapping("/me")
    @Transactional
    public ResponseEntity<AdminMeResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
        tenantSupport.applyTenant(em);
        AdminUser admin = adminUserRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(new AdminMeResponse(
                admin.getId(), admin.getEmail(), admin.getNombre(), admin.getRol(), admin.isActivo()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwtService.invalidate(authHeader.substring(7));
        }
        return ResponseEntity.noContent().build();
    }
}
