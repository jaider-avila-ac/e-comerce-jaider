package jaider.ecommerce.sucursal;

import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SucursalService {

    private final SucursalRepository sucursalRepository;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    // Lista liviana para selectores (alta de colaborador, filtros de pedidos/reportes) —
    // cualquier miembro del staff autenticado puede consultarla, igual que
    // PedidoService.listarColaboradores(). RLS ya restringe al tenant actual.
    @Transactional(readOnly = true)
    public List<SucursalResponse> listar() {
        tenantSupport.applyTenant(em);
        return sucursalRepository.findByActivoTrueOrderByNombreAsc().stream()
                .map(s -> new SucursalResponse(s.getId(), s.getNombre(), s.getWhatsapp()))
                .toList();
    }
}
