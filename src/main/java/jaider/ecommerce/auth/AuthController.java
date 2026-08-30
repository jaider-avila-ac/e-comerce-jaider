package jaider.ecommerce.auth;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.auth.admin.AdminUser;
import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.auth.dto.AdminMeResponse;
import jaider.ecommerce.auth.dto.LoginRequest;
import jaider.ecommerce.auth.dto.LoginResponse;
import jaider.ecommerce.auth.dto.SeleccionarTiendaRequest;
import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final TiendaRepository tiendaRepository;
    private final AuditoriaService auditoriaService;

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

            // Un superadmin (tienda_id NULL por diseño, ver chk_admin_users_superadmin) NUNCA
            // debe recibir automáticamente el tenant 1 — el plan multi-tenant exige selección
            // explícita y auditada de la tienda sobre la que va a actuar (§11.2). El JWT que
            // recibe acá NO lleva tnd_id (generate(..., null) omite el claim) — con eso solo
            // pasa el chequeo de rol de SecurityConfig (.hasAnyRole(...)), pero cualquier
            // endpoint tenantizado sigue rechazándolo (TenantContext queda sin fijar, cada
            // servicio ya exige "Sin contexto de tenant" o RLS no devuelve filas). La única
            // forma de operar sobre una tienda es pasar por /superadmin/seleccionar-tienda.
            Long tndIdParaToken = admin.getTiendaId(); // null si es superadmin
            String token = jwtService.generate(admin.getEmail(), admin.getRol(), tndIdParaToken);

            rateLimiter.registrarExito(identificador);
            return ResponseEntity.ok(new LoginResponse(
                    token, expirationMs, admin.getEmail(), admin.getNombre(), tndIdParaToken, admin.getRol()
            ));

        } catch (AuthenticationException e) {
            rateLimiter.registrarFallo(identificador);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Credenciales incorrectas"));
        }
    }

    /**
     * §11.2: "El superadministrador... debe seleccionar explícitamente una tienda. La selección
     * debe generar contexto autorizado y auditable." Requiere el JWT sin tenant que devuelve
     * login() para una cuenta superadmin — @PreAuthorize revalida el rol server-side (nunca
     * confiar solo en que el JWT diga "superadmin", el rol real es el de la BD en este momento).
     * Devuelve un JWT nuevo, esta vez SÍ con tnd_id, ya utilizable en el resto de la API.
     */
    @PostMapping("/superadmin/seleccionar-tienda")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @Transactional
    public ResponseEntity<LoginResponse> seleccionarTienda(@AuthenticationPrincipal UserDetails userDetails,
                                                            @Valid @RequestBody SeleccionarTiendaRequest req) {
        AdminUser admin = adminUserRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        if (!"superadmin".equals(admin.getRol())) {
            // Defensa adicional: @PreAuthorize ya lo exige, pero el rol pudo cambiar en la BD
            // entre que se emitió el JWT y ahora — nunca confiar en una autorización vieja.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos superadministrador");
        }

        Tienda tienda = tiendaRepository.findById(req.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
        if (!tienda.isActivo()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esa tienda está inactiva");
        }

        String token = jwtService.generate(admin.getEmail(), admin.getRol(), tienda.getId());

        TenantContext.set(tienda.getId().toString());
        try {
            tenantSupport.applyTenant(em);
            auditoriaService.registrar(tienda.getId(), admin.getId(), "superadmin.seleccion_tienda",
                    "tienda", tienda.getId(), Map.of("email_superadmin", admin.getEmail()));
        } finally {
            TenantContext.clear();
        }

        return ResponseEntity.ok(new LoginResponse(
                token, expirationMs, admin.getEmail(), admin.getNombre(), tienda.getId(), admin.getRol()
        ));
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
