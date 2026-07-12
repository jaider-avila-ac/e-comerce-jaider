package jaider.ecommerce.notificacion;

import jaider.ecommerce.notificacion.dto.NotificacionAdminResponse;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Historial de notificaciones para los admins de la tienda actual (compartido entre todo el staff). */
@RestController
@RequestMapping("/api/v1/notificaciones")
@RequiredArgsConstructor
public class NotificacionAdminController {

    private final NotificacionAdminRepository repo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    @Transactional(readOnly = true)
    public List<NotificacionAdminResponse> listar() {
        tenantSupport.applyTenant(em);
        return repo.findTop50ByOrderByCreadoEnDesc().stream().map(this::toResponse).toList();
    }

    @PostMapping("/{id}/leer")
    @Transactional
    public void marcarLeida(@PathVariable Long id) {
        tenantSupport.applyTenant(em);
        repo.marcarLeida(id);
    }

    @PostMapping("/leer-todas")
    @Transactional
    public void marcarTodasLeidas() {
        tenantSupport.applyTenant(em);
        repo.marcarTodasLeidas();
    }

    private NotificacionAdminResponse toResponse(NotificacionAdmin n) {
        return new NotificacionAdminResponse(
                n.getId(), n.getTipo(), n.getTitulo(), n.getCuerpo(),
                n.getEntidadTipo(), n.getEntidadId(), n.getLeidaEn() != null, n.getCreadoEn()
        );
    }
}
