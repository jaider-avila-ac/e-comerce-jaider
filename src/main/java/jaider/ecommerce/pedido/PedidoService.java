package jaider.ecommerce.pedido;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.notificacion.event.AlertaStockResueltaEvent;
import jaider.ecommerce.notificacion.event.PedidoEstadoCambiadoEvent;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final AuditoriaService auditoriaService;

    @PersistenceContext
    private EntityManager em;

    private static final Set<String> ESTADOS_VALIDOS = Set.of(
            "pendiente_pago", "pagado", "preparando", "enviado", "entregado", "cancelado", "devuelto"
    );

    private static final Map<String, Set<String>> TRANSICIONES_VALIDAS = Map.of(
            "pendiente_pago", Set.of("pagado", "cancelado"),
            "pagado", Set.of("preparando", "cancelado", "devuelto"),
            "preparando", Set.of("enviado", "cancelado", "devuelto"),
            "enviado", Set.of("entregado", "devuelto"),
            "entregado", Set.of("devuelto"),
            "cancelado", Set.of(),
            "devuelto", Set.of()
    );

    // Estados alcanzables solo después de que el pago se confirmó y PagoConfirmacionService
    // ya descontó el stock de las variantes (ver descontarStock allá).
    private static final Set<String> ESTADOS_CON_STOCK_DESCONTADO = Set.of(
            "pagado", "preparando", "enviado", "entregado"
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
    public PedidoResponse updateEstado(Long id, String estado, Long adminId) {
        tenantSupport.applyTenant(em);

        if (estado == null || !ESTADOS_VALIDOS.contains(estado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Estado inválido. Valores permitidos: " + ESTADOS_VALIDOS);
        }

        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        String estadoAnterior = p.getEstado();
        validarTransicion(estadoAnterior, estado);

        if (p.isAlertaStock() && "preparando".equals(estado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este pedido tiene un problema de stock sin resolver — revísalo antes de prepararlo");
        }

        // Si el pedido ya había descontado stock (pago confirmado) y ahora se cancela o
        // se devuelve, hay que restaurarlo — si no, esas unidades quedan perdidas del
        // inventario para siempre aunque el pedido nunca se haya entregado.
        boolean debeRestaurarStock = ESTADOS_CON_STOCK_DESCONTADO.contains(estadoAnterior)
                && ("cancelado".equals(estado) || "devuelto".equals(estado));
        if (debeRestaurarStock) {
            restaurarStock(id);
        }

        // pedidoRepo.updateEstado usa clearAutomatically=true: a partir de aquí "p" queda
        // detached, así que mutarlo ya no puede disparar un flush a mitad de los UPDATE nativos
        // (el flush de una entidad Pedido managed re-escribe ped_estado sin el CAST que necesita
        // el enum de Postgres y revienta — ver resolverAlertaStock más abajo para el mismo caso).
        pedidoRepo.updateEstado(id, estado);
        p.setEstado(estado);
        if (debeRestaurarStock) {
            p.setAlertaStock(false);
        }

        em.createNativeQuery(
                "INSERT INTO pedido_historial_estados (phe_ped_id, phe_estado, phe_admin_id) " +
                "VALUES (:pedId, CAST(:estado AS estado_pedido), :adminId)")
                .setParameter("pedId", id)
                .setParameter("estado", estado)
                .setParameter("adminId", adminId)
                .executeUpdate();

        if (adminId != null) {
            auditoriaService.registrar(p.getTndId(), adminId, "pedido.cambio_estado", "pedido", id,
                    Map.of("estado_anterior", estadoAnterior, "estado_nuevo", estado));
        }

        // Canal lateral: se publica al terminar el commit de esta transacción (ver
        // NotificacionEventListener), nunca puede retrasar ni afectar esta respuesta.
        eventPublisher.publishEvent(new PedidoEstadoCambiadoEvent(p.getTndId(), p.getUsrId(), id, p.getNumero(), estado));

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        return toResponse(p, clientMap.get(p.getUsrId()), null);
    }

    // ─── Link de seguimiento de envío ───────────────────────────────────────

    /** El admin pega aquí el link de seguimiento que le da la transportadora (ej. Coordinadora).
     *  Sin restricción de estado: puede agregarse o corregirse en cualquier momento. */
    @Transactional
    public PedidoResponse updateLinkSeguimiento(Long id, String link) {
        tenantSupport.applyTenant(em);

        String linkLimpio = (link != null && !link.isBlank()) ? link.trim() : null;
        if (linkLimpio != null && !linkLimpio.startsWith("http://") && !linkLimpio.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El link debe empezar por http:// o https://");
        }

        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        pedidoRepo.updateLinkSeguimiento(id, linkLimpio);
        p.setLinkSeguimiento(linkLimpio);

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        return toResponse(p, clientMap.get(p.getUsrId()), null);
    }

    // ─── Alerta de stock ─────────────────────────────────────────────────────

    /** El admin confirma que ya resolvió manualmente el faltante de stock (reabasteció,
     *  reembolsó parcialmente, contactó al cliente, etc.) y el pedido puede seguir su curso. */
    @Transactional
    public PedidoResponse resolverAlertaStock(Long id) {
        tenantSupport.applyTenant(em);

        if (!pedidoRepo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }

        // UPDATE nativo dirigido (no repo.save() ni tocar la entidad gestionada): cualquier
        // entidad Pedido cargada en este contexto de persistencia se marcaría "sucia" y
        // Hibernate reescribiría toda la fila al hacer flush, incluida ped_estado (enum de
        // Postgres) sin el CAST explícito que necesita — ver PedidoRepository.updateEstado.
        em.createNativeQuery("UPDATE pedidos SET ped_alerta_stock = false WHERE ped_id = :id")
                .setParameter("id", id)
                .executeUpdate();
        em.createNativeQuery("UPDATE pedido_items SET pi_stock_insuficiente = false WHERE pi_ped_id = :pedId")
                .setParameter("pedId", id)
                .executeUpdate();
        em.clear();

        Pedido p = pedidoRepo.findById(id).orElseThrow();

        eventPublisher.publishEvent(new AlertaStockResueltaEvent(p.getTndId(), id, p.getNumero()));

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        List<PedidoItem> items = itemRepo.findByPedIdOrderByIdAsc(id);
        return toResponse(p, clientMap.get(p.getUsrId()), items);
    }

    // ─── Helpers internos ──────────────────────────────────────────────────

    /**
     * Devuelve al stock de cada variante la cantidad que se descontó al confirmar el pago.
     * Excluye los items marcados {@code pi_stock_insuficiente}: esos nunca llegaron a
     * descontarse (ver PagoConfirmacionService.descontarStock), así que restaurarlos
     * inflaría el inventario con unidades que jamás salieron.
     */
    private void restaurarStock(Long pedId) {
        em.createNativeQuery("""
                UPDATE variantes v
                SET var_stock = var_stock + pi.pi_cantidad
                FROM pedido_items pi
                WHERE pi.pi_ped_id = :pedId
                  AND pi.pi_var_id = v.var_id
                  AND pi.pi_stock_insuficiente = false
                """)
                .setParameter("pedId", pedId)
                .executeUpdate();

        // El pedido queda en un estado terminal — cualquier alerta de stock pendiente
        // ya no aplica (no hay forma de seguir preparándolo).
        em.createNativeQuery("UPDATE pedidos SET ped_alerta_stock = false WHERE ped_id = :pedId")
                .setParameter("pedId", pedId)
                .executeUpdate();
    }

    private void validarTransicion(String actual, String siguiente) {
        if (Objects.equals(actual, siguiente)) return;

        Set<String> permitidos = TRANSICIONES_VALIDAS.getOrDefault(actual, Set.of());
        if (!permitidos.contains(siguiente)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transicion de estado no permitida: " + actual + " -> " + siguiente);
        }
    }

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
                p.isAlertaStock(),
                p.getLinkSeguimiento(),
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
                item.getSubtotalCentavos() / 100L,
                item.isStockInsuficiente()
        );
    }
}
