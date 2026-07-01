package jaider.ecommerce.pedido;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PedidoService {

    private final PedidoRepository pedidoRepo;
    private final PedidoItemRepository itemRepo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    private static final Set<String> ESTADOS_VALIDOS = Set.of(
            "pendiente_pago", "pagado", "preparando", "enviado", "entregado", "cancelado", "devuelto"
    );

    // ─── Listado ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PedidoResponse> getAll(String estado) {
        tenantSupport.applyTenant(em);

        List<Pedido> pedidos = (estado != null && !estado.isBlank())
                ? pedidoRepo.findByEstado(estado)
                : pedidoRepo.findAllOrdered();

        if (pedidos.isEmpty()) return List.of();

        Set<Long> usrIds = pedidos.stream().map(Pedido::getUsrId).collect(Collectors.toSet());
        Map<Long, String[]> clientMap = loadClientInfo(usrIds);

        return pedidos.stream()
                .map(p -> toResponse(p, clientMap.get(p.getUsrId()), null))
                .toList();
    }

    // ─── Detalle ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PedidoResponse getById(Long id) {
        tenantSupport.applyTenant(em);

        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        List<PedidoItem> items = itemRepo.findByPedIdOrderByIdAsc(id);

        return toResponse(p, clientMap.get(p.getUsrId()), items);
    }

    // ─── Cambio de estado ──────────────────────────────────────────────────

    @Transactional
    public PedidoResponse updateEstado(Long id, String estado) {
        tenantSupport.applyTenant(em);

        if (estado == null || !ESTADOS_VALIDOS.contains(estado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Estado inválido. Valores permitidos: " + ESTADOS_VALIDOS);
        }

        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        pedidoRepo.updateEstado(id, estado);
        p.setEstado(estado);

        em.createNativeQuery(
                "INSERT INTO pedido_historial_estados (phe_ped_id, phe_estado) " +
                "VALUES (:pedId, CAST(:estado AS estado_pedido))")
                .setParameter("pedId", id)
                .setParameter("estado", estado)
                .executeUpdate();

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        return toResponse(p, clientMap.get(p.getUsrId()), null);
    }

    // ─── Helpers internos ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<Long, String[]> loadClientInfo(Set<Long> usrIds) {
        if (usrIds.isEmpty()) return Map.of();

        List<Object[]> rows = em.createNativeQuery(
                "SELECT u.usr_id, u.usr_email, cp.cp_nombre, cp.cp_apellido " +
                "FROM usuarios u " +
                "LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = u.usr_id " +
                "WHERE u.usr_id IN :ids"
        ).setParameter("ids", usrIds).getResultList();

        Map<Long, String[]> map = new HashMap<>();
        for (Object[] row : rows) {
            Long usrId = ((Number) row[0]).longValue();
            String email   = (String) row[1];
            String nombre  = (String) row[2];
            String apellido = (String) row[3];
            map.put(usrId, new String[]{ email, nombre, apellido });
        }
        return map;
    }

    private PedidoResponse toResponse(Pedido p, String[] clientInfo, List<PedidoItem> items) {
        String clienteEmail = clientInfo != null ? clientInfo[0] : "";
        String nombre   = clientInfo != null ? clientInfo[1] : null;
        String apellido = clientInfo != null ? clientInfo[2] : null;
        String clienteNombre = (nombre != null && !nombre.isBlank())
                ? nombre + (apellido != null && !apellido.isBlank() ? " " + apellido : "")
                : clienteEmail;

        List<PedidoItemResponse> itemsList = items != null
                ? items.stream().map(this::toItemResponse).toList()
                : null;

        return new PedidoResponse(
                p.getId(),
                p.getNumero(),
                p.getEstado(),
                clienteNombre,
                clienteEmail,
                p.getDirSnapshot(),
                p.getSubtotalCentavos() / 100L,
                p.getDescuentoCentavos() / 100L,
                p.getEnvioCentavos() / 100L,
                p.getTotalCentavos() / 100L,
                p.getNotas(),
                p.getCreadoEn(),
                itemsList
        );
    }

    private PedidoItemResponse toItemResponse(PedidoItem item) {
        return new PedidoItemResponse(
                item.getId(),
                item.getPrdId(),
                item.getVarId(),
                item.getNombreSnap(),
                item.getImagenSnap(),
                item.getVariantesSnap(),
                item.getPrecioUnitarioCentavos() / 100L,
                item.getDescuentoUnitarioCentavos() / 100L,
                item.getCantidad(),
                item.getSubtotalCentavos() / 100L
        );
    }
}
