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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/admin")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Value("${jwt.expiration-ms:86400000}")
    private long expirationMs;

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            Authentication auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email(), req.password())
            );
            UserDetails user = (UserDetails) auth.getPrincipal();

            tenantSupport.applyTenant(em);
            AdminUser admin = adminUserRepository.findByEmail(user.getUsername()).orElseThrow();
            // superadmin no tiene tienda_id; para el panel admin siempre es calzacaribe = 1
            Long tndId = admin.getTiendaId() != null ? admin.getTiendaId() : 1L;
            String token = jwtService.generate(admin.getEmail(), admin.getRol(), tndId);

            return ResponseEntity.ok(new LoginResponse(
                    token, expirationMs, admin.getEmail(), admin.getNombre(), tndId, admin.getRol()
            ));

        } catch (BadCredentialsException e) {
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
