package jaider.ecommerce.catalogo.pregunta;

import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/preguntas")
@RequiredArgsConstructor
public class PreguntaAdminController {

    private final PreguntaService preguntaService;
    private final AdminUserRepository adminUserRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    public List<PreguntaAdminResponse> listar(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) Long prdId) {
        return preguntaService.listarAdmin(estado, prdId);
    }

    @GetMapping("/{id}/historial")
    public List<PreguntaEdicionResponse> historial(@PathVariable Long id) {
        return preguntaService.historial(id);
    }

    @PutMapping("/{id}/responder")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void responder(@AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id, @RequestBody ResponderPreguntaRequest req) {
        Long adminId = resolverAdminId(userDetails);
        preguntaService.responder(id, adminId, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable Long id) {
        preguntaService.eliminarAdmin(id);
    }

    // Mismo patrón que PedidoController.resolverAdminId — necesita @Transactional en el método
    // del controller (no solo en el service) para que applyTenant() y findByEmail() compartan
    // la misma transacción: set_config(..., true) es un SET LOCAL, solo dura la transacción
    // activa (ver el bug real encontrado y corregido en ReporteController).
    private Long resolverAdminId(UserDetails userDetails) {
        if (userDetails == null) return null;
        tenantSupport.requireTenant(em);
        return adminUserRepository.findByEmail(userDetails.getUsername())
                .map(a -> a.getId())
                .orElse(null);
    }
}
