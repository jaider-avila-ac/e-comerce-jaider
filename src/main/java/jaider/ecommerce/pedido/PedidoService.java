package jaider.ecommerce.pedido;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.notificacion.event.AlertaStockResueltaEvent;
import jaider.ecommerce.notificacion.event.PedidoCanceladoEvent;
import jaider.ecommerce.notificacion.event.PedidoEstadoCambiadoEvent;
import jaider.ecommerce.pago.reembolso.ReembolsoRepository;
import jaider.ecommerce.pago.reembolso.ReembolsoResponse;
import jaider.ecommerce.pago.reembolso.ReembolsoService;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
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
    private final ReembolsoService reembolsoService;
    private final ReembolsoRepository reembolsoRepo;

    @PersistenceContext
    private EntityManager em;

    private static final Set<String> ESTADOS_VALIDOS = Set.of(
            "pendiente_pago", "pagado", "preparando", "enviado", "entregado", "cancelado", "devuelto"
    );

    // Catálogo fijo de motivos de cancelación por admin — igual filosofía que ESTADOS_VALIDOS:
    // se valida en Java, la columna es un varchar simple (no un enum de Postgres) porque solo
    // se escribe desde este único método, nunca desde SQL ad-hoc.
    private static final Map<String, String> MOTIVOS_CANCELACION = new LinkedHashMap<>() {{
        put("producto_agotado", "Producto agotado");
        put("producto_inconveniente", "Producto con inconvenientes");
        put("error_precio", "Error en el precio o la publicación");
        put("envio_imposible", "Imposibilidad de realizar el envío");
        put("compra_duplicada", "Compra duplicada");
        put("acordado_cliente", "Solicitud acordada con el cliente");
        put("pago_no_confirmado", "Pago no confirmado a tiempo");
        put("otro", "Otro motivo");
    }};

    // Flujo activo del pedido, en orden. El admin puede moverse a cualquiera de estos estados
    // desde cualquier otro (saltar hacia adelante, ej. pagado -> entregado, o retroceder si se
    // equivocó) — ver validarTransicion(). "pendiente_pago" nunca es un destino manual: solo se
    // asigna al crear el pedido.
    private static final List<String> ORDEN_ACTIVO = List.of(
            "pendiente_pago", "pagado", "preparando", "enviado", "entregado"
    );

    private static final Set<String> ESTADOS_TERMINALES = Set.of("cancelado", "devuelto");

    // Estados alcanzables solo después de que el pago se confirmó y PagoConfirmacionService
    // ya descontó el stock de las variantes (ver descontarStock allá).
    private static final Set<String> ESTADOS_CON_STOCK_DESCONTADO = Set.of(
            "pagado", "preparando", "enviado", "entregado"
    );

    @Transactional(readOnly = true)
    public Map<String, Long> conteosPorEstado() {
        tenantSupport.applyTenant(em);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT ped_estado::text, COUNT(*) FROM pedidos GROUP BY ped_estado").getResultList();
        Map<String, Long> conteos = new LinkedHashMap<>();
        long total = 0L;
        for (Object[] row : rows) {
            long cantidad = ((Number) row[1]).longValue();
            conteos.put(String.valueOf(row[0]), cantidad);
            total += cantidad;
        }
        conteos.put("total", total);
        return conteos;
    }

    // ─── Listado ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PedidoResponse> getAll(String estado, Long colaboradorId) {
        tenantSupport.applyTenant(em);

        List<Pedido> pedidos = (estado != null && !estado.isBlank())
                ? pedidoRepo.findByEstado(estado, colaboradorId)
                : pedidoRepo.findAllOrdered(colaboradorId);

        if (pedidos.isEmpty()) return List.of();

        Set<Long> usrIds = pedidos.stream().map(Pedido::getUsrId).collect(Collectors.toSet());
        Map<Long, String[]> clientMap = loadClientInfo(usrIds);
        Map<Long, String> colaboradorMap = loadColaboradorInfo(pedidos.stream()
                .map(Pedido::getColaboradorId).filter(Objects::nonNull).collect(Collectors.toSet()));

        return pedidos.stream()
                .map(p -> toResponse(p, clientMap.get(p.getUsrId()), null, colaboradorMap.get(p.getColaboradorId())))
                .toList();
    }

    /** Lista liviana de staff (admin/colaborador/bodega) para el selector de filtro/reasignación
     *  de pedidos — no pasa por AdminUserController (restringido a admin/superadmin), cualquier
     *  miembro del staff puede consultar quién puede tomar/gestionar un pedido. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listarColaboradores() {
        tenantSupport.applyTenant(em);
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, nombre FROM admin_users WHERE activo = true AND rol <> 'superadmin' ORDER BY nombre ASC")
                .getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row[0]);
            item.put("nombre", row[1]);
            result.add(item);
        }
        return result;
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

        // Si se saltaron pasos hacia adelante (ej. pagado -> entregado), se dejan registrados
        // en el historial los estados intermedios que nunca se marcaron explícitamente, para
        // que el seguimiento del pedido (stepper del cliente y del admin) no se vea incompleto.
        int idxActual = ORDEN_ACTIVO.indexOf(estadoAnterior);
        int idxNuevo = ORDEN_ACTIVO.indexOf(estado);
        if (idxActual >= 0 && idxNuevo > idxActual + 1) {
            for (int i = idxActual + 1; i < idxNuevo; i++) {
                insertarHistorial(id, ORDEN_ACTIVO.get(i), adminId,
                        "Marcado automáticamente al saltar directo a \"" + estado + "\"");
            }
        }

        insertarHistorial(id, estado, adminId, null);

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

    // ─── Responsable del pedido ─────────────────────────────────────────────

    /** Cualquier miembro del staff puede tomar un pedido sin asignar. Si ya lo tiene otro
     *  colaborador, se rechaza — evita que dos personas gestionen el mismo pedido a la vez. */
    @Transactional
    public PedidoResponse asignarme(Long id, Long adminId) {
        tenantSupport.applyTenant(em);
        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        if (p.getColaboradorId() != null && !p.getColaboradorId().equals(adminId)) {
            String nombreActual = resolverColaboradorNombre(p.getColaboradorId());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya lo está gestionando " + (nombreActual != null ? nombreActual : "otro colaborador"));
        }
        if (!Objects.equals(p.getColaboradorId(), adminId)) {
            pedidoRepo.asignarColaborador(id, adminId);
            p.setColaboradorId(adminId);
            if (adminId != null) {
                auditoriaService.registrar(p.getTndId(), adminId, "pedido.asignado", "pedido", id, Map.of());
            }
        }

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        return toResponse(p, clientMap.get(p.getUsrId()), null);
    }

    /** Reasignar o quitar el responsable (colaboradorId nullable) — solo admin/superadmin,
     *  sin la validación de "ya está tomado" (es un override intencional). */
    @Transactional
    public PedidoResponse asignar(Long id, Long colaboradorId, Long adminId) {
        tenantSupport.applyTenant(em);
        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        pedidoRepo.asignarColaborador(id, colaboradorId);
        p.setColaboradorId(colaboradorId);
        if (adminId != null) {
            auditoriaService.registrar(p.getTndId(), adminId, "pedido.reasignado", "pedido", id,
                    Map.of("colaborador_id", String.valueOf(colaboradorId)));
        }

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        return toResponse(p, clientMap.get(p.getUsrId()), null);
    }

    // ─── Cancelación por el admin + reembolso ──────────────────────────────

    @Transactional
    public PedidoResponse cancelarPorAdmin(Long id, String motivo, String motivoOtro, String nota, Long adminId) {
        tenantSupport.applyTenant(em);

        if (motivo == null || !MOTIVOS_CANCELACION.containsKey(motivo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Motivo inválido. Valores permitidos: " + MOTIVOS_CANCELACION.keySet());
        }
        if ("otro".equals(motivo) && (motivoOtro == null || motivoOtro.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Explica el motivo de la cancelación en el campo \"otro motivo\"");
        }

        // Guard explícito: validarTransicion() (dentro de updateEstado) trata actual==siguiente
        // como no-op silencioso — sin este chequeo, recancelar un pedido ya "cancelado" volvería
        // a crear un reembolso duplicado en vez de ser rechazado.
        Pedido actual = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
        if (ESTADOS_TERMINALES.contains(actual.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El pedido ya está en un estado final (" + actual.getEstado() + ") y no se puede cancelar de nuevo");
        }

        // Reusa la transición normal (valida que no esté "entregado" — ahí corresponde
        // devolución, no cancelación — y restaura stock si ya se había descontado).
        updateEstado(id, "cancelado", adminId);

        String motivoOtroLimpio = "otro".equals(motivo) && motivoOtro != null ? motivoOtro.trim() : null;
        String notaLimpia = (nota != null && !nota.isBlank()) ? nota.trim() : null;
        OffsetDateTime ahora = OffsetDateTime.now();
        pedidoRepo.registrarCancelacion(id, motivo, motivoOtroLimpio, notaLimpia, adminId, ahora);

        Pedido p = pedidoRepo.findById(id).orElseThrow();

        Long pagId = buscarUltimoPagoAprobado(id);
        if (pagId != null) {
            Long refId = reembolsoService.crear(pagId, id, p.getUsrId(), p.getTotalCentavos(),
                    "Cancelación: " + MOTIVOS_CANCELACION.get(motivo), "cancelacion_admin");
            reembolsoService.procesarAutomatico(refId);
        }

        if (adminId != null) {
            auditoriaService.registrar(p.getTndId(), adminId, "pedido.cancelado_admin", "pedido", id,
                    Map.of("motivo", motivo, "motivo_otro", motivoOtroLimpio == null ? "" : motivoOtroLimpio,
                            "nota", notaLimpia == null ? "" : notaLimpia));
        }

        eventPublisher.publishEvent(new PedidoCanceladoEvent(p.getTndId(), p.getUsrId(), id, p.getNumero(),
                MOTIVOS_CANCELACION.get(motivo), notaLimpia));

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        return toResponse(p, clientMap.get(p.getUsrId()), null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getHistorialEstados(Long id) {
        tenantSupport.applyTenant(em);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT h.phe_estado::text, h.phe_nota, h.phe_creado_en, a.nombre
                FROM pedido_historial_estados h
                LEFT JOIN admin_users a ON a.id = h.phe_admin_id
                WHERE h.phe_ped_id = :id
                ORDER BY h.phe_creado_en ASC, h.phe_id ASC
                """)
                .setParameter("id", id)
                .getResultList();

        List<Map<String, Object>> historial = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("estado", row[0]);
            item.put("nota", row[1]);
            item.put("fecha", row[2]);
            item.put("admin", row[3]);
            historial.add(item);
        }
        return historial;
    }

    private Long buscarUltimoPagoAprobado(Long pedId) {
        try {
            return ((Number) em.createNativeQuery("""
                    SELECT pag_id FROM pagos WHERE pag_ped_id = :pedId AND pag_estado = CAST('APPROVED' AS estado_pago)
                    ORDER BY pag_id DESC LIMIT 1
                    """)
                    .setParameter("pedId", pedId)
                    .getSingleResult()).longValue();
        } catch (NoResultException e) {
            return null;
        }
    }

    // ─── Seguimiento de envío ────────────────────────────────────────────────

    private static final Set<String> MOSTRAR_SEGUIMIENTO_VALIDOS = Set.of("codigo", "link", "ambos");

    /** El admin registra la transportadora (nombre libre, la lista fija vive en el frontend),
     *  el código de rastreo y/o el link que le dio, y elige qué le muestra a la tienda. Sin
     *  restricción de estado: puede agregarse o corregirse en cualquier momento. */
    @Transactional
    public PedidoResponse updateSeguimiento(Long id, String transportadora, String codigoRastreo,
                                             String link, String mostrar) {
        tenantSupport.applyTenant(em);

        String linkLimpio = (link != null && !link.isBlank()) ? link.trim() : null;
        if (linkLimpio != null && !linkLimpio.startsWith("http://") && !linkLimpio.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El link debe empezar por http:// o https://");
        }
        String mostrarLimpio = (mostrar != null && !mostrar.isBlank()) ? mostrar.trim() : null;
        if (mostrarLimpio != null && !MOSTRAR_SEGUIMIENTO_VALIDOS.contains(mostrarLimpio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Valor inválido, debe ser: " + MOSTRAR_SEGUIMIENTO_VALIDOS);
        }
        String transportadoraLimpia = (transportadora != null && !transportadora.isBlank())
                ? transportadora.trim() : null;
        String codigoLimpio = (codigoRastreo != null && !codigoRastreo.isBlank()) ? codigoRastreo.trim() : null;

        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        pedidoRepo.updateSeguimiento(id, transportadoraLimpia, codigoLimpio, linkLimpio, mostrarLimpio);
        p.setTransportadora(transportadoraLimpia);
        p.setCodigoRastreo(codigoLimpio);
        p.setLinkSeguimiento(linkLimpio);
        p.setMostrarSeguimiento(mostrarLimpio);

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

    private void insertarHistorial(Long pedId, String estado, Long adminId, String nota) {
        em.createNativeQuery(
                "INSERT INTO pedido_historial_estados (phe_ped_id, phe_estado, phe_admin_id, phe_nota) " +
                "VALUES (:pedId, CAST(:estado AS estado_pedido), :adminId, :nota)")
                .setParameter("pedId", pedId)
                .setParameter("estado", estado)
                .setParameter("adminId", adminId)
                .setParameter("nota", nota)
                .executeUpdate();
    }

    private void validarTransicion(String actual, String siguiente) {
        if (Objects.equals(actual, siguiente)) return;

        if (ESTADOS_TERMINALES.contains(actual)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El pedido ya está en un estado final (" + actual + ") y no se puede cambiar");
        }
        if ("pendiente_pago".equals(siguiente)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede volver a \"pago pendiente\" manualmente");
        }
        if ("cancelado".equals(siguiente)) {
            if ("entregado".equals(actual)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Un pedido ya entregado no se puede cancelar — usa \"devuelto\"");
            }
            return;
        }
        if ("devuelto".equals(siguiente)) {
            if ("pendiente_pago".equals(actual)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Un pedido sin pagar no se puede devolver");
            }
            return;
        }
        if (!ORDEN_ACTIVO.contains(siguiente)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transición de estado no permitida: " + actual + " -> " + siguiente);
        }
        // Entre estados del flujo activo (pagado, preparando, enviado, entregado) se permite
        // avanzar saltando pasos o retroceder libremente — ver updateEstado() para el relleno
        // automático del historial cuando se salta hacia adelante.
    }

    @SuppressWarnings("unchecked")
    private Map<Long, String> loadColaboradorInfo(Set<Long> colaboradorIds) {
        // HashMap, no Map.of(): getAll() consulta este mapa con p.getColaboradorId(), que es
        // null para cualquier pedido sin asignar — Map.of().get(null) lanza NPE (los mapas
        // inmutables de Map.of rechazan null incluso en get()), HashMap.get(null) simplemente
        // devuelve null.
        if (colaboradorIds.isEmpty()) return new HashMap<>();
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, nombre FROM admin_users WHERE id IN :ids")
                .setParameter("ids", colaboradorIds).getResultList();
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return map;
    }

    private String resolverColaboradorNombre(Long colaboradorId) {
        if (colaboradorId == null) return null;
        return loadColaboradorInfo(Set.of(colaboradorId)).get(colaboradorId);
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
        return toResponse(p, clientInfo, items, resolverColaboradorNombre(p.getColaboradorId()));
    }

    private PedidoResponse toResponse(Pedido p, String[] clientInfo, List<PedidoItem> items, String colaboradorNombre) {
        String clienteEmail = clientInfo != null ? clientInfo[0] : "";
        String nombre   = clientInfo != null ? clientInfo[1] : null;
        String apellido = clientInfo != null ? clientInfo[2] : null;
        String clienteNombre = (nombre != null && !nombre.isBlank())
                ? nombre + (apellido != null && !apellido.isBlank() ? " " + apellido : "")
                : clienteEmail;

        List<PedidoItemResponse> itemsList = items != null
                ? items.stream().map(this::toItemResponse).toList()
                : null;

        // Método de pago y reembolso solo se resuelven en el detalle (items != null), igual
        // que la lista de ítems — evita N+1 queries al listar todos los pedidos.
        boolean detalle = items != null;
        String metodoPago = detalle ? obtenerMetodoPago(p.getId()) : null;
        ReembolsoResponse reembolso = detalle ? obtenerReembolso(p.getId()) : null;

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
                p.getTransportadora(),
                p.getCodigoRastreo(),
                p.getMostrarSeguimiento(),
                p.getConfirmadoClienteEn(),
                metodoPago,
                p.getCancelMotivo(),
                p.getCancelMotivoOtro(),
                p.getCancelNota(),
                p.getCanceladoEn(),
                reembolso,
                p.getColaboradorId(),
                colaboradorNombre,
                itemsList
        );
    }

    private String obtenerMetodoPago(Long pedId) {
        try {
            return (String) em.createNativeQuery("""
                    SELECT pag_metodo::text FROM pagos WHERE pag_ped_id = :pedId AND pag_estado = CAST('APPROVED' AS estado_pago)
                    ORDER BY pag_id DESC LIMIT 1
                    """)
                    .setParameter("pedId", pedId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    private ReembolsoResponse obtenerReembolso(Long pedId) {
        return reembolsoRepo.findByPedIdOrderByIdDesc(pedId).stream().findFirst()
                .map(r -> new ReembolsoResponse(r.getId(), r.getEstado(), r.getMontoCentavos(),
                        r.getGatewayRef(), r.getErrorMensaje(), r.getCreadoEn(), r.getConfirmadoEn()))
                .orElse(null);
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
