package jaider.ecommerce.pedido;

import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.envio.EnvioGuiaService;
import jaider.ecommerce.tienda.envio.GenerarGuiaRequest;
import jaider.ecommerce.tienda.envio.GuiaGenerada;
import jaider.ecommerce.tienda.envio.PrepararEnvioResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Preparar envío" + generación de guía real con Envia para un pedido — PLAN_INTEGRACION_
 * ENVIA.md, Fase 4. Solo aplica a tiendas en modo 'envia' (ver {@link EnvioGuiaService}).
 * Consultar cotizaciones ({@code preparar}) es de solo lectura, igual que el resto de
 * /api/v1/pedidos/** (staff autenticado) — pero generar la guía real SÍ cobra de la cuenta de
 * Envia de la tienda, así que queda restringido a ADMIN, igual que
 * cancelar/corregir-estado/asignar en {@link PedidoController} (corrección de auditoría,
 * 2026-09-01 — antes cualquier COLABORADOR/BODEGA podía generarla).
 */
@RestController
@RequestMapping("/api/v1/pedidos/{id}/envio")
@RequiredArgsConstructor
public class PedidoEnvioController {

    private final EnvioGuiaService envioGuiaService;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @GetMapping("/preparar")
    public PrepararEnvioResponse preparar(@PathVariable Long id) {
        return envioGuiaService.preparar(tndIdActual(), id);
    }

    @PostMapping("/generar-guia")
    @PreAuthorize("hasRole('ADMIN')")
    public GuiaGenerada generarGuia(@PathVariable Long id, @RequestBody GenerarGuiaRequest req,
                                     @AuthenticationPrincipal UserDetails userDetails) {
        return envioGuiaService.generarGuia(tndIdActual(), id, req, resolverAdminId(userDetails));
    }

    private Long tndIdActual() {
        String tndId = TenantContext.get();
        if (tndId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        return Long.parseLong(tndId);
    }

    private Long resolverAdminId(UserDetails userDetails) {
        if (userDetails == null) return null;
        tenantSupport.requireTenant(em);
        return adminUserRepository.findByEmail(userDetails.getUsername())
                .map(a -> a.getId())
                .orElse(null);
    }
}
