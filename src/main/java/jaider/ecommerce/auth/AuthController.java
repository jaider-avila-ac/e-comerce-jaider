package jaider.ecommerce.auth;

import jaider.ecommerce.auth.admin.Empleado;
import jaider.ecommerce.auth.admin.EmpleadoRepository;
import jaider.ecommerce.auth.dto.AdminMeResponse;
import jaider.ecommerce.auth.dto.LoginRequest;
import jaider.ecommerce.auth.dto.LoginResponse;
import jaider.ecommerce.auth.jwt.JwtService;
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
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/admin")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final EmpleadoRepository empleadoRepository;

    @Value("${jwt.expiration-ms:86400000}")
    private long expirationMs;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            Authentication auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email(), req.password())
            );
            UserDetails user = (UserDetails) auth.getPrincipal();

            Empleado emp = empleadoRepository.findByEmail(user.getUsername()).orElseThrow();
            // superadmin no tiene tnd_id; para el panel admin siempre es calzacaribe = 1
            Long tndId = emp.getTndId() != null ? emp.getTndId() : 1L;
            String token = jwtService.generate(emp.getEmail(), emp.getRol(), tndId);

            return ResponseEntity.ok(new LoginResponse(
                    token, expirationMs, emp.getEmail(), emp.getNombre(), tndId, emp.getRol()
            ));

        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Credenciales incorrectas"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<AdminMeResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
        Empleado emp = empleadoRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(new AdminMeResponse(
                emp.getId(), emp.getEmail(), emp.getNombre(), emp.getRol(), emp.isActivo()
        ));
    }
}
