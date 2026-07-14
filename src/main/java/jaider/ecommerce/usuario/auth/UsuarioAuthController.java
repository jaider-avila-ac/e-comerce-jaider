package jaider.ecommerce.usuario.auth;

import jakarta.validation.Valid;
import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.usuario.auth.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/auth/tienda")
@RequiredArgsConstructor
public class UsuarioAuthController {

    private final UsuarioAuthService service;
    private final JwtService jwtService;

    private Long currentTnd() {
        String id = TenantContext.get();
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id requerido");
        }
        return Long.parseLong(id);
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody TiendaRegisterRequest req) {
        service.preRegister(req, currentTnd());
        return ResponseEntity.accepted().body(Map.of("message", "Código enviado al correo"));
    }

    @PostMapping("/verify")
    public TiendaAuthResponse verify(@Valid @RequestBody TiendaVerifyRequest req) {
        return service.verifyAndRegister(req, currentTnd());
    }

    @PostMapping("/resend-code")
    public ResponseEntity<Map<String, String>> resend(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email requerido");
        }
        service.resendCode(email, currentTnd());
        return ResponseEntity.ok(Map.of("message", "Nuevo código enviado"));
    }

    @PostMapping("/login")
    public TiendaAuthResponse login(@Valid @RequestBody TiendaLoginRequest req) {
        return service.login(req, currentTnd());
    }

    @PostMapping("/google")
    public TiendaAuthResponse googleLogin(@Valid @RequestBody TiendaGoogleRequest req) {
        return service.googleLogin(req, currentTnd());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody TiendaForgotPasswordRequest req) {
        service.forgotPassword(req, currentTnd());
        return ResponseEntity.ok(Map.of("message", "Si el correo existe recibirás un código"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody TiendaResetPasswordRequest req) {
        service.resetPassword(req, currentTnd());
        return ResponseEntity.ok(Map.of("message", "Contraseña actualizada"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwtService.invalidate(authHeader.substring(7));
        }
        return ResponseEntity.noContent().build();
    }
}
