package jaider.ecommerce.pedido.devolucion;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.infra.CloudinaryService;
import jaider.ecommerce.pago.reembolso.ReembolsoService;
import jaider.ecommerce.pedido.PedidoService;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Solicitudes de devolución de producto (RMA), a nivel de todo el pedido (no por ítem).
 * El reembolso monetario en sí no se automatiza: al confirmar que el producto físico volvió,
 * se crea una fila en "reembolsos" en estado pendiente para que el admin la procese aparte.
 */
@Service
@RequiredArgsConstructor
public class SolicitudDevolucionService {

    private static final Set<String> TIPOS_VALIDOS = Set.of("retracto", "defecto");

    // Derecho de retracto: plazo legal de 5 días hábiles desde la entrega (art. 47 Ley 1480/2011).
    private static final int DIAS_HABILES_RETRACTO = 5;

    // Cambio por defecto de fábrica: la política no fija un número de días exacto ("dentro del
    // periodo de garantía legal estipulado para el tipo de producto") — se usa este plazo como
    // tope operativo razonable para no dejarlo indefinido, no es un límite que imponga la ley.
    private static final int DIAS_PLAZO_DEFECTO = 30;

    private final SolicitudDevolucionRepository repo;
    private final SolicitudDevolucionFotoRepository fotoRepo;
    private final DireccionDevolucionRepository direccionRepo;
    private final TenantSupport tenantSupport;
    private final CloudinaryService cloudinaryService;
    private final AuditoriaService auditoriaService;
    private final ReembolsoService reembolsoService;
    private final PedidoService pedidoService;

    @PersistenceContext
    private EntityManager em;

    // ─── Cliente ────────────────────────────────────────────────────────────

    @Transactional
    public SolicitudDevolucionResponse crear(Long usrId, Long tndId, String numero, SolicitudDevolucionRequest req) {
        tenantSupport.applyTenant(em);

        if (req.motivo() == null || req.motivo().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el motivo de la devolución");
        }
        String tipo = req.tipo() == null ? "" : req.tipo().trim();
        if (!TIPOS_VALIDOS.contains(tipo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Indica el tipo de solicitud: \"retracto\" (cambié de opinión) o \"defecto\" (defecto de fábrica)");
        }

        Object[] row = buscarPedido(numero, usrId, tndId);
        Long pedId = ((Number) row[0]).longValue();
        String estadoPedido = (String) row[1];

        if (!"entregado".equals(estadoPedido)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se puede solicitar devolución de un pedido ya entregado");
        }

        if ("retracto".equals(tipo)) {
            if (diasHabilesDesdeEntrega(pedId) >= DIAS_HABILES_RETRACTO) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El plazo legal de " + DIAS_HABILES_RETRACTO + " días hábiles para ejercer el derecho " +
                        "de retracto ya venció. Si el producto tiene un defecto de fábrica, solicita el cambio " +
                        "por esa vía.");
            }
        } else if (!dentroDePlazoDevolucion(pedId, DIAS_PLAZO_DEFECTO)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El plazo de " + DIAS_PLAZO_DEFECTO + " días para solicitar el cambio ya venció. " +
                    "Contáctanos directamente si tu producto sigue en garantía.");
        }

        if (repo.findActivaByPedId(pedId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay una solicitud de devolución activa para este pedido");
        }

        // INSERT nativo (no repo.save()): igual que Pedido, omite sod_estado para que tome el
        // DEFAULT ('pendiente') — un save() de JPA no puede pasar el valor sin CAST, y si
        // encima se mutara el campo en memoria después sobre la entidad managed, Hibernate
        // intentaría un UPDATE del enum sin CAST en el siguiente flush.
        Number idNum = (Number) em.createNativeQuery("""
                INSERT INTO solicitudes_devolucion (sod_tnd_id, sod_ped_id, sod_tipo, sod_motivo)
                VALUES (:tndId, :pedId, :tipo, :motivo)
                RETURNING sod_id
                """)
                .setParameter("tndId", tndId)
                .setParameter("pedId", pedId)
                .setParameter("tipo", tipo)
                .setParameter("motivo", req.motivo().trim())
                .getSingleResult();
        Long solicitudId = idNum.longValue();

        guardarFotos(solicitudId, req.fotoUrls());

        SolicitudDevolucion s = repo.findById(solicitudId).orElseThrow();
        return toResponse(s, numero);
    }

    @Transactional(readOnly = true)
    public Optional<SolicitudDevolucionResponse> obtenerPorPedido(Long usrId, Long tndId, String numero) {
        tenantSupport.applyTenant(em);
        Object[] row = buscarPedido(numero, usrId, tndId);
        Long pedId = ((Number) row[0]).longValue();

        return repo.findByPedIdOrderByCreadoEnDesc(pedId).stream()
                .findFirst()
                .map(s -> toResponse(s, numero));
    }

    @Transactional
    public SolicitudDevolucionResponse registrarCodigoRastreo(Long usrId, Long tndId, String numero, String codigo) {
        tenantSupport.applyTenant(em);
        if (codigo == null || codigo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el número de guía");
        }

        Object[] row = buscarPedido(numero, usrId, tndId);
        Long pedId = ((Number) row[0]).longValue();
        SolicitudDevolucion s = repo.findActivaByPedId(pedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay una solicitud de devolución activa"));

        if (!"aprobada".equals(s.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Todavía no se puede registrar la guía — espera a que el admin apruebe la devolución");
        }

        repo.registrarCodigoRastreo(s.getId(), codigo.trim());
        s.setEstado("en_transito");
        s.setCodigoRastreo(codigo.trim());
        return toResponse(s, numero);
    }

    @Transactional
    public void cancelar(Long usrId, Long tndId, String numero) {
        tenantSupport.applyTenant(em);
        Object[] row = buscarPedido(numero, usrId, tndId);
        Long pedId = ((Number) row[0]).longValue();
        SolicitudDevolucion s = repo.findActivaByPedId(pedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay una solicitud de devolución activa"));

        if (!"pendiente".equals(s.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se puede cancelar mientras está pendiente de revisión");
        }

        eliminarFotosDeCloudinary(s.getId());
        repo.updateEstado(s.getId(), "cancelada");
    }

    // ─── Admin ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SolicitudDevolucionResponse> getAll(String estado) {
        tenantSupport.applyTenant(em);
        List<SolicitudDevolucion> lista = (estado != null && !estado.isBlank())
                ? repo.findByEstado(estado)
                : repo.findAllOrdered();
        return lista.stream().map(s -> toResponse(s, numeroDePedido(s.getPedId()))).toList();
    }

    @Transactional(readOnly = true)
    public SolicitudDevolucionResponse getById(Long id) {
        tenantSupport.applyTenant(em);
        SolicitudDevolucion s = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));
        return toResponse(s, numeroDePedido(s.getPedId()));
    }

    @Transactional
    public SolicitudDevolucionResponse aprobar(Long id, Long direccionId, String nota, Long adminId) {
        tenantSupport.applyTenant(em);
        SolicitudDevolucion s = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));
        if (!"pendiente".equals(s.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se puede aprobar una solicitud pendiente");
        }
        DireccionDevolucion dir = direccionRepo.findById(direccionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dirección de devolución no encontrada"));
        if (!dir.isActivo()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esa dirección de devolución está inactiva");
        }

        String notaLimpia = (nota != null && !nota.isBlank()) ? nota.trim() : null;
        OffsetDateTime ahora = OffsetDateTime.now();
        repo.aprobarORechazar(id, "aprobada", direccionId, notaLimpia, ahora);
        s.setEstado("aprobada");
        s.setDvdId(direccionId);
        s.setAdminNota(notaLimpia);
        s.setRevisadoEn(ahora);

        if (adminId != null) {
            auditoriaService.registrar(s.getTndId(), adminId, "devolucion.aprobada", "solicitud_devolucion", id,
                    Map.of("direccion_id", direccionId, "nota", notaLimpia == null ? "" : notaLimpia));
        }
        return toResponse(s, numeroDePedido(s.getPedId()));
    }

    @Transactional
    public SolicitudDevolucionResponse rechazar(Long id, String nota, Long adminId) {
        tenantSupport.applyTenant(em);
        SolicitudDevolucion s = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));
        if (!"pendiente".equals(s.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se puede rechazar una solicitud pendiente");
        }
        if (nota == null || nota.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el motivo del rechazo");
        }

        OffsetDateTime ahora = OffsetDateTime.now();
        repo.aprobarORechazar(id, "rechazada", null, nota.trim(), ahora);
        s.setEstado("rechazada");
        s.setAdminNota(nota.trim());
        s.setRevisadoEn(ahora);

        eliminarFotosDeCloudinary(id);
        if (adminId != null) {
            auditoriaService.registrar(s.getTndId(), adminId, "devolucion.rechazada", "solicitud_devolucion", id,
                    Map.of("nota", nota.trim()));
        }
        return toResponse(s, numeroDePedido(s.getPedId()));
    }

    @Transactional
    public SolicitudDevolucionResponse confirmarRecibida(Long id, Long adminId) {
        tenantSupport.applyTenant(em);
        SolicitudDevolucion s = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));
        if (!"en_transito".equals(s.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se puede confirmar recepción de una devolución en tránsito (con guía registrada)");
        }

        OffsetDateTime ahora = OffsetDateTime.now();
        repo.confirmarRecibida(id, ahora);
        s.setEstado("recibida");
        s.setRecibidaEn(ahora);

        crearReembolsoPendiente(s);
        // Único lugar donde un pedido llega a "devuelto" — nunca desde el selector genérico
        // de estado (ver PedidoService.transicionarPorDevolucion y F-05/F-03 de la auditoría).
        pedidoService.transicionarPorDevolucion(s.getPedId(), adminId);
        if (adminId != null) {
            auditoriaService.registrar(s.getTndId(), adminId, "devolucion.recibida", "solicitud_devolucion", id, Map.of());
        }
        return toResponse(s, numeroDePedido(s.getPedId()));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private Object[] buscarPedido(String numero, Long usrId, Long tndId) {
        try {
            return (Object[]) em.createNativeQuery("""
                    SELECT ped_id, ped_estado::text
                    FROM pedidos WHERE ped_numero = :numero AND ped_usr_id = :usrId AND ped_tnd_id = :tndId
                    """)
                    .setParameter("numero", numero)
                    .setParameter("usrId", usrId)
                    .setParameter("tndId", tndId)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }
    }

    private String numeroDePedido(Long pedId) {
        return (String) em.createNativeQuery("SELECT ped_numero FROM pedidos WHERE ped_id = :id")
                .setParameter("id", pedId).getSingleResult();
    }

    /** Si la transición más reciente a "entregado" ocurrió dentro del plazo (días calendario) —
     *  la comparación de fechas se hace en SQL (evita traer el timestamp a Java, donde MAX() sobre
     *  una columna timestamptz vuelve como Instant en vez de OffsetDateTime y revienta el cast).
     *  Mismo criterio que PedidoAutoConfirmacionService: el historial, nunca ped_actualizado_en. */
    private boolean dentroDePlazoDevolucion(Long pedId, int dias) {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM pedido_historial_estados
                WHERE phe_ped_id = :pedId AND phe_estado = 'entregado'
                  AND phe_creado_en >= now() - (INTERVAL '1 day' * :dias)
                """)
                .setParameter("pedId", pedId)
                .setParameter("dias", dias)
                .getSingleResult();
        return count.longValue() > 0;
    }

    /** Cuenta los días hábiles (lunes a viernes) transcurridos desde el día de la entrega hasta
     *  hoy, sin contar el propio día de entrega — así "5 días hábiles contados a partir de la
     *  entrega" (art. 47 Ley 1480/2011) se cumple exactamente. No descuenta festivos colombianos
     *  (no hay calendario de festivos en el sistema); en la práctica da al cliente 1-2 días de
     *  margen adicional en semanas con festivo, nunca menos de los 5 hábiles que exige la ley. */
    private int diasHabilesDesdeEntrega(Long pedId) {
        Number dias = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM generate_series(
                    date_trunc('day', (
                        SELECT MAX(phe_creado_en) FROM pedido_historial_estados
                        WHERE phe_ped_id = :pedId AND phe_estado = 'entregado'
                    )) + INTERVAL '1 day',
                    date_trunc('day', now()),
                    INTERVAL '1 day'
                ) d
                WHERE EXTRACT(ISODOW FROM d) < 6
                """)
                .setParameter("pedId", pedId)
                .getSingleResult();
        return dias.intValue();
    }

    private void guardarFotos(Long solicitudId, List<String> urls) {
        if (urls == null) return;
        short orden = 0;
        for (String url : urls) {
            if (url == null || url.isBlank()) continue;
            em.createNativeQuery(
                    "INSERT INTO solicitud_devolucion_fotos (sdf_sod_id, sdf_url, sdf_orden) VALUES (:sodId, :url, :orden)")
                    .setParameter("sodId", solicitudId)
                    .setParameter("url", url)
                    .setParameter("orden", orden++)
                    .executeUpdate();
        }
    }

    private void eliminarFotosDeCloudinary(Long solicitudId) {
        fotoRepo.findBySodIdOrderByOrdenAscIdAsc(solicitudId)
                .forEach(f -> cloudinaryService.delete(f.getUrl()));
    }

    /** Crea el reembolso e intenta procesarlo automáticamente contra la pasarela — mismo camino
     *  compartido con la cancelación de pedido por el admin (ver ReembolsoService). Si no hay un
     *  pago aprobado para el pedido (no debería pasar), simplemente no hay nada que reembolsar. */
    private void crearReembolsoPendiente(SolicitudDevolucion s) {
        Long pagId;
        try {
            // Una sola columna seleccionada: getSingleResult() devuelve el valor directo
            // (Number), no un Object[] — a diferencia de las consultas multi-columna de abajo.
            pagId = ((Number) em.createNativeQuery("""
                    SELECT pag_id FROM pagos WHERE pag_ped_id = :pedId AND pag_estado = CAST('APPROVED' AS estado_pago)
                    ORDER BY pag_id DESC LIMIT 1
                    """)
                    .setParameter("pedId", s.getPedId())
                    .getSingleResult()).longValue();
        } catch (NoResultException e) {
            return; // sin pago aprobado no hay nada que reembolsar (no debería pasar)
        }

        Object[] pedido = (Object[]) em.createNativeQuery(
                "SELECT ped_usr_id, ped_total_centavos, ped_numero FROM pedidos WHERE ped_id = :id")
                .setParameter("id", s.getPedId())
                .getSingleResult();
        Long usrId = ((Number) pedido[0]).longValue();
        Long totalCentavos = ((Number) pedido[1]).longValue();
        String numero = (String) pedido[2];

        Long refId = reembolsoService.crear(pagId, s.getPedId(), usrId, totalCentavos,
                "Devolución " + numero, "devolucion_cliente");
        reembolsoService.procesarAutomatico(refId);
    }

    private SolicitudDevolucionResponse toResponse(SolicitudDevolucion s, String numeroPedido) {
        DireccionDevolucionResponse direccion = null;
        if (s.getDvdId() != null) {
            direccion = direccionRepo.findById(s.getDvdId()).map(d -> new DireccionDevolucionResponse(
                    d.getId(), d.getNombre(), d.getDireccion(), d.getComplemento(), d.getDepartamento(),
                    d.getMunicipio(), d.getBarrio(), d.getContactoNombre(), d.getContactoTelefono(), d.isActivo()
            )).orElse(null);
        }
        List<String> fotos = fotoRepo.findBySodIdOrderByOrdenAscIdAsc(s.getId())
                .stream().map(SolicitudDevolucionFoto::getUrl).toList();

        return new SolicitudDevolucionResponse(
                s.getId(), s.getPedId(), numeroPedido, s.getEstado(), s.getTipo(), s.getMotivo(), direccion,
                s.getCodigoRastreo(), s.getAdminNota(), s.getCreadoEn(), s.getRevisadoEn(), s.getRecibidaEn(), fotos
        );
    }
}
