package jaider.ecommerce.tienda.integracion;

import jaider.ecommerce.auth.admin.AdminUser;
import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.superadmin.CampoEstadoResponse;
import jaider.ecommerce.tienda.superadmin.WompiCredencialesRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Credenciales de Wompi — las gestiona el propio admin de CADA tienda (decisión explícita del
 * usuario, 2026-10-15), a diferencia de Resend/Cloudinary/Envia que solo configura el superadmin:
 * la cuenta de Wompi es del comerciante, no de la plataforma.
 *
 * El tenant se resuelve SIEMPRE del JWT de este admin (TenantContext), nunca de un parámetro de
 * la URL — un admin de una tienda no tiene forma de ni siquiera referirse a otra tienda desde
 * este endpoint. Mismo patrón que TiendaConfigController/EmpaqueController: solo ADMIN, nunca
 * COLABORADOR/BODEGA (son credenciales de dinero).
 */
@RestController
@RequestMapping("/api/v1/tienda/wompi")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class TiendaWompiController {

    private final TiendaWompiCredencialesService service;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    public List<CampoEstadoResponse> estado() {
        return service.estado(tndIdActual());
    }

    @PutMapping
    public List<CampoEstadoResponse> actualizar(@RequestBody WompiCredencialesRequest req,
                                                 @AuthenticationPrincipal UserDetails principal) {
        return service.guardar(tndIdActual(), req, adminIdDe(principal), "tienda_admin");
    }

    private Long tndIdActual() {
        String id = TenantContext.get();
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        return Long.parseLong(id);
    }

    private Long adminIdDe(UserDetails principal) {
        tenantSupport.requireTenant(em);
        return adminUserRepository.findByEmail(principal.getUsername())
                .map(AdminUser::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin no encontrado"));
    }
}
