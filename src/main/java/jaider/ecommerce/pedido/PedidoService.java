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

    // Único paso siguiente válido por estado activo — la máquina de estados avanza de a un paso
    // por vez desde el endpoint genérico (ver AUDITORIA_FUNCIONAL_ECOMMERCE.md, F-05). Saltar
    // pasos o retroceder ya no es una transición "normal": requiere corregirEstado(), que exige
    // motivo y queda auditado. "pendiente_pago" nunca es un destino manual — solo lo asigna la
    // confirmación de pago (PagoConfirmacionService).
    private static final Map<String, String> SIGUIENTE_PASO = Map.of(
            "pagado", "preparando",
            "preparando", "enviado",
            "enviado", "entregado"
    );

    // Estados que sí acepta corregirEstado() como destino de una corrección excepcional —
    // "pendiente_pago" queda fuera (solo lo asigna el pago) y "cancelado"/"devuelto" tienen
    // flujo propio (cancelarPorAdmin / SolicitudDevolucionService), nunca un selector genérico.
    private static final Set<String> ESTADOS_CORREGIBLES = Set.of("pagado", "preparando", "enviado", "entregado");

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
    public List<PedidoResponse> getAll(String estado, Long colaboradorId, Long sucursalId) {
        tenantSupport.applyTenant(em);

        List<Pedido> pedidos = (estado != null && !estado.isBlank())
                ? pedidoRepo.findByEstado(estado, colaboradorId, sucursalId)
                : pedidoRepo.findAllOrdered(colaboradorId, sucursalId);

        if (pedidos.isEmpty()) return List.of();

        Set<Long> usrIds = pedidos.stream().map(Pedido::getUsrId).collect(Collectors.toSet());
        Map<Long, String[]> clientMap = loadClientInfo(usrIds);
        Map<Long, String> colaboradorMap = loadColaboradorInfo(pedidos.stream()
                .map(Pedido::getColaboradorId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> sucursalMap = loadSucursalInfo(pedidos.stream()
                .map(Pedido::getSucursalId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> metodoPagoMap = loadMetodoPagoInfo(pedidos.stream()
                .map(Pedido::getId).collect(Collectors.toSet()));

        return pedidos.stream()
                .map(p -> toResponse(p, clientMap.get(p.getUsrId()), null,
                        colaboradorMap.get(p.getColaboradorId()), sucursalMap.get(p.getSucursalId()),
                        metodoPagoMap.get(p.getId())))
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

    /** Avance normal, de a un paso: pagado→preparando→enviado→entregado. Cualquier salto,
     *  retroceso, o llegada a cancelado/devuelto se rechaza acá — esos casos van por
     *  corregirEstado(), cancelarPorAdmin() o el flujo de devoluciones, respectivamente
     *  (ver AUDITORIA_FUNCIONAL_ECOMMERCE.md, F-05). */
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
        validarSiguientePaso(estadoAnterior, estado);

        if (p.isAlertaStock() && "preparando".equals(estado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este pedido tiene un problema de stock sin resolver — revísalo antes de prepararlo");
        }
        // Sin esto, un pedido podía llegar a "preparando"/"enviado" sin que nadie lo hubiera
        // tomado nunca — nadie era responsable de prepararlo físicamente. Se exige acá, en el
        // paso de avance normal, no en corregirEstado() (esa es la vía de override explícito del
        // admin para casos excepcionales, y no debe quedar más restringida que antes).
        if (("preparando".equals(estado) || "enviado".equals(estado)) && p.getColaboradorId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Asigna un responsable a este pedido antes de marcarlo como " + estado);
        }
        if ("enviado".equals(estado)) {
            validarSeguimientoRegistrado(p);
        }

        aplicarTransicion(p, estado, adminId, null);

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

    /** Corrección excepcional (saltar o retroceder entre estados activos) — solo admin/
     *  superadmin, exige motivo y queda auditada. No inventa etapas intermedias en el
     *  historial: solo registra el estado real al que se corrigió y por qué. */
    @Transactional
    public PedidoResponse corregirEstado(Long id, String estado, String motivo, Long adminId) {
        tenantSupport.applyTenant(em);

        if (estado == null || !ESTADOS_CORREGIBLES.contains(estado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se puede corregir a uno de estos estados: " + ESTADOS_CORREGIBLES);
        }
        if (motivo == null || motivo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Explica el motivo de la corrección");
        }

        Pedido p = pedidoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        String estadoAnterior = p.getEstado();
        if (ESTADOS_TERMINALES.contains(estadoAnterior)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El pedido ya está en un estado final (" + estadoAnterior + ") y no se puede corregir");
        }
        if (estado.equals(estadoAnterior)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pedido ya está en ese estado");
        }
        if ("enviado".equals(estado)) {
            validarSeguimientoRegistrado(p);
        }

        String motivoLimpio = motivo.trim();
        aplicarTransicion(p, estado, adminId, "Corrección manual: " + motivoLimpio);

        if (adminId != null) {
            auditoriaService.registrar(p.getTndId(), adminId, "pedido.correccion_estado", "pedido", id,
                    Map.of("estado_anterior", estadoAnterior, "estado_nuevo", estado, "motivo", motivoLimpio));
        }

        eventPublisher.publishEvent(new PedidoEstadoCambiadoEvent(p.getTndId(), p.getUsrId(), id, p.getNumero(), estado));

        Map<Long, String[]> clientMap = loadClientInfo(Set.of(p.getUsrId()));
        return toResponse(p, clientMap.get(p.getUsrId()), null);
    }

    /** Efecto de negocio de un cambio de estado, sin auditoría ni evento — eso lo decide cada
     *  método llamador (updateEstado, corregirEstado, cancelarPorAdmin, transicionarPorDevolucion)
     *  porque cada uno registra una acción de auditoría distinta.
     *
     *  Compare-and-set (updateEstadoSi en vez de updateEstado): el llamador siempre lee el pedido
     *  antes de decidir la transición, pero entre esa lectura y este UPDATE puede haber pasado
     *  otra solicitud concurrente sobre el MISMO pedido (doble clic en "cancelar", dos admins
     *  gestionando la misma devolución a la vez). Sin esto, ambas pasarían la validación con el
     *  estado viejo y ambas seguirían adelante — en cancelarPorAdmin() eso significa crear DOS
     *  reembolsos por el mismo pedido. Si el UPDATE no afecta ninguna fila, alguien más ya ganó
     *  la carrera y se rechaza con 409 en vez de continuar a ciegas. */
    private void aplicarTransicion(Pedido p, String estadoDestino, Long adminId, String notaHistorial) {
        int actualizadas = pedidoRepo.updateEstadoSi(p.getId(), estadoDestino, p.getEstado());
        if (actualizadas == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este pedido ya fue modificado por otra acción — recarga la página e intenta de nuevo.");
        }

        // Si el pedido ya había descontado stock (pago confirmado) y ahora se cancela o
        // se devuelve, hay que restaurarlo — si no, esas unidades quedan perdidas del
        // inventario para siempre aunque el pedido nunca se haya entregado.
        boolean debeRestaurarStock = ESTADOS_CON_STOCK_DESCONTADO.contains(p.getEstado())
                && ("cancelado".equals(estadoDestino) || "devuelto".equals(estadoDestino));
        if (debeRestaurarStock) {
            restaurarStock(p.getId());
        }

        // updateEstadoSi usa clearAutomatically=true: a partir de aquí "p" queda detached, así
        // que mutarlo ya no puede disparar un flush a mitad de los UPDATE nativos (el flush de
        // una entidad Pedido managed re-escribe ped_estado sin el CAST que necesita el enum de
        // Postgres y revienta — ver resolverAlertaStock más abajo para el mismo caso).
        p.setEstado(estadoDestino);
        if (debeRestaurarStock) {
            p.setAlertaStock(false);
        }

        insertarHistorial(p.getId(), estadoDestino, adminId, notaHistorial);
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
            p.setSucursalId(resolverSucursalIdDeColaborador(adminId));
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
        p.setSucursalId(resolverSucursalIdDeColaborador(colaboradorId));
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

        if ("entregado".equals(actual.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un pedido ya entregado no se puede cancelar — usa el flujo de devolución");
        }

        // aplicarTransicion restaura stock si ya se había descontado — no pasa por updateEstado()
        // porque ese método ya no acepta "cancelado" como destino (ver F-05: cancelar solo por
        // este flujo dedicado, con motivo obligatorio, nunca desde el selector genérico).
        aplicarTransicion(actual, "cancelado", adminId, null);

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

    // ─── Devolución (posventa) ──────────────────────────────────────────────

    /** Único camino hacia "devuelto" — lo dispara SolicitudDevolucionService cuando el admin
     *  confirma que el producto físico volvió, nunca un selector genérico (ver F-05 y F-03).
     *  No-op defensivo si el pedido ya está en un estado final (no debería pasar: solo se
     *  invoca desde una solicitud de devolución "recibida", que ya exigió "entregado" al crearse). */
    @Transactional
    public void transicionarPorDevolucion(Long pedId, Long adminId) {
        tenantSupport.applyTenant(em);
        Pedido p = pedidoRepo.findById(pedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
        if (ESTADOS_TERMINALES.contains(p.getEstado())) {
            return;
        }

        aplicarTransicion(p, "devuelto", adminId, "Confirmado por el flujo de devoluciones");
        eventPublisher.publishEvent(new PedidoEstadoCambiadoEvent(p.getTndId(), p.getUsrId(), pedId, p.getNumero(), "devuelto"));
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

    /** Solo permite el único paso siguiente definido en SIGUIENTE_PASO — nada de saltos,
     *  retrocesos, ni llegar a cancelado/devuelto/pendiente_pago por acá (ver F-05). */
    private void validarSiguientePaso(String actual, String siguiente) {
        if (ESTADOS_TERMINALES.contains(actual)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El pedido ya está en un estado final (" + actual + ") y no se puede cambiar");
        }
        if ("cancelado".equals(siguiente)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para cancelar usa el botón \"Cancelar compra\" — exige motivo y gestiona el reembolso");
        }
        if ("devuelto".equals(siguiente)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "\"Devuelto\" se alcanza automáticamente cuando se confirma la devolución del producto");
        }
        String pasoValido = SIGUIENTE_PASO.get(actual);
        if (!Objects.equals(pasoValido, siguiente)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, pasoValido != null
                    ? "Desde \"" + actual + "\" solo puedes avanzar a \"" + pasoValido + "\""
                    : "El pedido no tiene un siguiente paso — usa una corrección si es necesario");
        }
    }

    /** El seguimiento (transportadora + guía) debe registrarse antes de marcar un pedido como
     *  enviado — si no, "enviado" no tiene con qué respaldarse. */
    private void validarSeguimientoRegistrado(Pedido p) {
        if (p.getTransportadora() == null || p.getTransportadora().isBlank()
                || p.getCodigoRastreo() == null || p.getCodigoRastreo().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Registra la transportadora y el número de guía antes de marcarlo como enviado");
        }
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

    /** Tienda física del colaborador — se copia al pedido al (re)asignarlo. Null si el
     *  colaborador no existe o quitó el responsable (colaboradorId null). */
    private Long resolverSucursalIdDeColaborador(Long colaboradorId) {
        if (colaboradorId == null) return null;
        try {
            Number sucursalId = (Number) em.createNativeQuery(
                    "SELECT sucursal_id FROM admin_users WHERE id = :id")
                    .setParameter("id", colaboradorId)
                    .getSingleResult();
            return sucursalId != null ? sucursalId.longValue() : null;
        } catch (NoResultException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Long, String> loadSucursalInfo(Set<Long> sucursalIds) {
        if (sucursalIds.isEmpty()) return new HashMap<>();
        List<Object[]> rows = em.createNativeQuery(
                "SELECT suc_id, suc_nombre FROM sucursales WHERE suc_id IN :ids")
                .setParameter("ids", sucursalIds).getResultList();
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return map;
    }

    /** Último pago APPROVED de cada pedido, en batch — misma idea que loadColaboradorInfo/
     *  loadSucursalInfo, para no lanzar una query por fila (obtenerMetodoPago) al listar. */
    @SuppressWarnings("unchecked")
    private Map<Long, String> loadMetodoPagoInfo(Set<Long> pedidoIds) {
        if (pedidoIds.isEmpty()) return new HashMap<>();
        List<Object[]> rows = em.createNativeQuery("""
                SELECT DISTINCT ON (pag_ped_id) pag_ped_id, pag_metodo::text
                FROM pagos
                WHERE pag_ped_id IN :ids AND pag_estado = CAST('APPROVED' AS estado_pago)
                ORDER BY pag_ped_id, pag_id DESC
                """)
                .setParameter("ids", pedidoIds).getResultList();
        Map<Long, String> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return map;
    }

    private String resolverSucursalNombre(Long sucursalId) {
        if (sucursalId == null) return null;
        return loadSucursalInfo(Set.of(sucursalId)).get(sucursalId);
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
        return toResponse(p, clientInfo, items, resolverColaboradorNombre(p.getColaboradorId()),
                resolverSucursalNombre(p.getSucursalId()));
    }

    private PedidoResponse toResponse(Pedido p, String[] clientInfo, List<PedidoItem> items,
                                       String colaboradorNombre, String sucursalNombre) {
        return toResponse(p, clientInfo, items, colaboradorNombre, sucursalNombre, null);
    }

    private PedidoResponse toResponse(Pedido p, String[] clientInfo, List<PedidoItem> items,
                                       String colaboradorNombre, String sucursalNombre,
                                       String metodoPagoPrecargado) {
        String clienteEmail = clientInfo != null ? clientInfo[0] : "";
        String nombre   = clientInfo != null ? clientInfo[1] : null;
        String apellido = clientInfo != null ? clientInfo[2] : null;
        String clienteNombre = (nombre != null && !nombre.isBlank())
                ? nombre + (apellido != null && !apellido.isBlank() ? " " + apellido : "")
                : clienteEmail;

        List<PedidoItemResponse> itemsList = items != null
                ? items.stream().map(this::toItemResponse).toList()
                : null;

        // El reembolso solo se resuelve en el detalle (items != null) — evita N+1 al listar.
        // El método de pago sí se necesita en la lista (columna "Método pago" en el panel), así
        // que en getAll() se precarga en batch (loadMetodoPagoInfo) y se pasa por acá; en el
        // detalle se resuelve individualmente vía obtenerMetodoPago, más preciso al no depender
        // de qué haya quedado precargado.
        boolean detalle = items != null;
        String metodoPago = detalle ? obtenerMetodoPago(p.getId()) : metodoPagoPrecargado;
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
                p.isEnvioContraEntrega(),
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
                p.getSucursalId(),
                sucursalNombre,
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
