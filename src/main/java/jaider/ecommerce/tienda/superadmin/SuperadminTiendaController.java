package jaider.ecommerce.tienda.superadmin;

import jaider.ecommerce.auth.admin.AdminUser;
import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.tienda.aprovisionamiento.TenantProvisioningResult;
import jaider.ecommerce.tienda.integracion.IntegracionSalud;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Backend para el futuro panel de superadmin (React, todavía NO se construye acá — 2026-08-31,
 * es otro proyecto). Deja listos los endpoints que ese panel va a consumir: crear tienda,
 * configurar sus credenciales de integración (cifradas, ver {@link SuperadminTiendaService}),
 * probarlas en vivo y activar/desactivar. Ninguna respuesta de este controller devuelve jamás
 * el valor de una credencial — solo si está "configurada".
 *
 * Restringido a SUPERADMIN vía SecurityConfig (todo /api/v1/superadmin/** ya exige ese rol) — el
 * mismo dominio separado del superadmin descrito en AuthController, sin acceso a ninguna otra
 * ruta operativa de tienda.
 */
@RestController
@RequestMapping("/api/v1/superadmin/tiendas")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERADMIN')")
public class SuperadminTiendaController {

    private final SuperadminTiendaService service;
    private final AdminUserRepository adminUserRepository;

    @PostMapping
    public ResponseEntity<TenantProvisioningResult> crear(@Valid @RequestBody CrearTiendaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crearBorrador(req));
    }

    @GetMapping
    public List<TiendaResumenResponse> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public TiendaDetalleResponse detalle(@PathVariable Long id) {
        return service.detalle(id);
    }

    @PutMapping("/{id}/integraciones/wompi")
    public List<CampoEstadoResponse> guardarWompi(@PathVariable Long id,
                                                   @Valid @RequestBody WompiCredencialesRequest req,
                                                   @AuthenticationPrincipal UserDetails principal) {
        return service.guardarWompi(id, req, adminIdDe(principal));
    }

    @PutMapping("/{id}/integraciones/resend")
    public List<CampoEstadoResponse> guardarResend(@PathVariable Long id,
                                                    @Valid @RequestBody ResendCredencialesRequest req,
                                                    @AuthenticationPrincipal UserDetails principal) {
        return service.guardarResend(id, req, adminIdDe(principal));
    }

    @PutMapping("/{id}/integraciones/cloudinary")
    public List<CampoEstadoResponse> guardarCloudinary(@PathVariable Long id,
                                                        @Valid @RequestBody CloudinaryCredencialesRequest req,
                                                        @AuthenticationPrincipal UserDetails principal) {
        return service.guardarCloudinary(id, req, adminIdDe(principal));
    }

    @PostMapping("/{id}/integraciones/verificar")
    public List<IntegracionSalud> verificar(@PathVariable Long id) {
        return service.verificar(id);
    }

    @PostMapping("/{id}/activar")
    public ResponseEntity<Void> activar(@PathVariable Long id) {
        service.activar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/desactivar")
    public ResponseEntity<Void> desactivar(@PathVariable Long id) {
        service.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    private Long adminIdDe(UserDetails principal) {
        return adminUserRepository.findByEmail(principal.getUsername())
                .map(AdminUser::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin no encontrado"));
    }
}
