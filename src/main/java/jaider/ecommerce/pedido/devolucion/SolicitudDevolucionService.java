package jaider.ecommerce.pedido.devolucion;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.infra.CloudinaryService;
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

/**
 * Solicitudes de devolución de producto (RMA), a nivel de todo el pedido (no por ítem).
 * El reembolso monetario en sí no se automatiza: al confirmar que el producto físico volvió,
 * se crea una fila en "reembolsos" en estado pendiente para que el admin la procese aparte.
 */
@Service
@RequiredArgsConstructor
public class SolicitudDevolucionService {

    private static final int DIAS_PLAZO_DEVOLUCION = 30;

    private final SolicitudDevolucionRepository repo;
    private final SolicitudDevolucionFotoRepository fotoRepo;
    private final DireccionDevolucionRepository direccionRepo;
    private final TenantSupport tenantSupport;
    private final CloudinaryService cloudinaryService;
    private final AuditoriaService auditoriaService;

    @PersistenceContext
    private EntityManager em;

    // ─── Cliente ────────────────────────────────────────────────────────────

    @Transactional
    public SolicitudDevolucionResponse crear(Long usrId, Long tndId, String numero, SolicitudDevolucionRequest req) {
        tenantSupport.applyTenant(em);

        if (req.motivo() == null || req.motivo().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el motivo de la devolución");
        }

        Object[] row = buscarPedido(numero, usrId, tndId);
        Long pedId = ((Number) row[0]).longValue();
        String estadoPedido = (String) row[1];

        if (!"entregado".equals(estadoPedido)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se puede solicitar devolución de un pedido ya entregado");
        }

        if (!dentroDePlazoDevolucion(pedId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El plazo de " + DIAS_PLAZO_DEVOLUCION + " días para solicitar devolución ya venció");
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
                INSERT INTO solicitudes_devolucion (sod_tnd_id, sod_ped_id, sod_motivo)
                VALUES (:tndId, :pedId, :motivo)
                RETURNING sod_id
                """)
                .setParameter("tndId", tndId)
                .setParameter("pedId", pedId)
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
    public SolicitudDevolucionResponse aprobar(Long id, Long direccionId, Long adminId) {
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

        OffsetDateTime ahora = OffsetDateTime.now();
        repo.aprobarORechazar(id, "aprobada", direccionId, null, ahora);
        s.setEstado("aprobada");
        s.setDvdId(direccionId);
        s.setRevisadoEn(ahora);

        if (adminId != null) {
            auditoriaService.registrar(s.getTndId(), adminId, "devolucion.aprobada", "solicitud_devolucion", id,
                    Map.of("direccion_id", direccionId));
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

    /** Si la transición más reciente a "entregado" ocurrió dentro del plazo — la comparación
     *  de fechas se hace en SQL (evita traer el timestamp a Java, donde MAX() sobre una
     *  columna timestamptz vuelve como Instant en vez de OffsetDateTime y revienta el cast).
     *  Mismo criterio que PedidoAutoConfirmacionService: el historial, nunca ped_actualizado_en. */
    private boolean dentroDePlazoDevolucion(Long pedId) {
        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM pedido_historial_estados
                WHERE phe_ped_id = :pedId AND phe_estado = 'entregado'
                  AND phe_creado_en >= now() - (INTERVAL '1 day' * :dias)
                """)
                .setParameter("pedId", pedId)
                .setParameter("dias", DIAS_PLAZO_DEVOLUCION)
                .getSingleResult();
        return count.longValue() > 0;
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

    /** No procesa el reembolso en la pasarela — solo dejo la fila lista en estado "pendiente"
     *  para que el admin lo gestione (ver alcance en el plan: fuera de esta función). */
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

        em.createNativeQuery("""
                INSERT INTO reembolsos (ref_pag_id, ref_ped_id, ref_usr_id, ref_monto_centavos, ref_motivo, ref_estado)
                VALUES (:pagId, :pedId, :usrId, :monto, :motivo, CAST('pendiente' AS estado_reembolso))
                """)
                .setParameter("pagId", pagId)
                .setParameter("pedId", s.getPedId())
                .setParameter("usrId", usrId)
                .setParameter("monto", totalCentavos)
                .setParameter("motivo", "Devolución " + numero)
                .executeUpdate();
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
                s.getId(), s.getPedId(), numeroPedido, s.getEstado(), s.getMotivo(), direccion,
                s.getCodigoRastreo(), s.getAdminNota(), s.getCreadoEn(), s.getRevisadoEn(), s.getRecibidaEn(), fotos
        );
    }
}
