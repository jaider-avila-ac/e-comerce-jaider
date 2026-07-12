package jaider.ecommerce.notificacion;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.notificacion.dto.NotificacionClienteResponse;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Historial de notificaciones del cliente autenticado en la tienda pública. */
@RestController
@RequestMapping("/api/v1/public/notificaciones")
@RequiredArgsConstructor
public class NotificacionClienteController {

    private final NotificacionClienteRepository repo;
    private final TenantSupport tenantSupport;
    private final JwtService jwtService;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    @Transactional(readOnly = true)
    public List<NotificacionClienteResponse> listar(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long[] ids = extractIds(authHeader);
        tenantSupport.applyTenant(em);
        return repo.findActivasByUsrId(ids[0]).stream().map(this::toResponse).toList();
    }

    @PostMapping("/{id}/leer")
    @Transactional
    public void marcarLeida(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        Long[] ids = extractIds(authHeader);
        tenantSupport.applyTenant(em);
        repo.marcarLeida(id, ids[0]);
    }

    @PostMapping("/leer-todas")
    @Transactional
    public void marcarTodasLeidas(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long[] ids = extractIds(authHeader);
        tenantSupport.applyTenant(em);
        repo.marcarTodasLeidas(ids[0]);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void eliminar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {
        Long[] ids = extractIds(authHeader);
        tenantSupport.applyTenant(em);
        repo.eliminar(id, ids[0]);
    }

    private NotificacionClienteResponse toResponse(NotificacionCliente n) {
        return new NotificacionClienteResponse(
                n.getId(), n.getTipo(), n.getTitulo(), n.getCuerpo(),
                n.getEntidadTipo(), n.getEntidadId(), n.getImagenUrl(),
                n.getLeidaEn() != null, n.getCreadoEn()
        );
    }

    private Long[] extractIds(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
        }
        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido");
        }
        Long usrId = jwtService.extractUsrId(token);
        Long tndId = jwtService.extractTndId(token);
        if (usrId == null || tndId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token sin usr_id");
        }
        TenantContext.set(tndId.toString());
        return new Long[]{usrId, tndId};
    }
}
