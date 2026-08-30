package jaider.ecommerce.notificacion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jaider.ecommerce.infra.ResendEmailService;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.TiendaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persiste y empuja notificaciones en tiempo real por WebSocket.
 *
 * Siempre se invoca desde un listener @Async fuera de la transacción de negocio original (ver
 * NotificacionEventListener), así que cualquier fallo aquí (BD o WebSocket caído) solo se registra
 * en el log — nunca puede afectar la respuesta ya entregada al usuario ni el flujo que la originó.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificacionService {

    private final TenantSupport tenantSupport;
    private final SimpMessagingTemplate messagingTemplate;
    private final TiendaRepository tiendaRepo;
    private final ResendEmailService emailService;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> METODO_PAGO_LABEL = Map.of(
            "CARD", "Tarjeta", "NEQUI", "Nequi", "PSE", "PSE",
            "BANCOLOMBIA_TRANSFER", "Transferencia Bancolombia", "EFECTIVO", "Efectivo", "OTRO", "Otro"
    );

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void notificarAdmin(Long tndId, String tipo, String titulo, String cuerpo,
                                String entidadTipo, Long entidadId) {
        try {
            TenantContext.set(tndId.toString());
            tenantSupport.applyTenant(em);

            Long id = ((Number) em.createNativeQuery("""
                    INSERT INTO notificaciones_admin
                        (nta_tnd_id, nta_tipo, nta_titulo, nta_cuerpo, nta_entidad_tipo, nta_entidad_id)
                    VALUES (:tndId, CAST(:tipo AS tipo_notificacion_admin), :titulo, :cuerpo, :entidadTipo, :entidadId)
                    RETURNING nta_id
                    """)
                    .setParameter("tndId", tndId)
                    .setParameter("tipo", tipo)
                    .setParameter("titulo", titulo)
                    .setParameter("cuerpo", cuerpo)
                    .setParameter("entidadTipo", entidadTipo)
                    .setParameter("entidadId", entidadId)
                    .getSingleResult()).longValue();

            messagingTemplate.convertAndSend("/topic/admin/" + tndId, Map.of(
                    "id", id,
                    "tipo", tipo,
                    "titulo", titulo,
                    "mensaje", cuerpo == null ? "" : cuerpo,
                    "entidad_tipo", entidadTipo == null ? "" : entidadTipo,
                    "entidad_id", entidadId,
                    "leida", false,
                    "creado_en", OffsetDateTime.now()
            ));
        } catch (Exception e) {
            log.error("[Notificaciones] No se pudo notificar a los admins de la tienda {}: {}",
                    tndId, e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    public void notificarCliente(Long tndId, Long usrId, String tipo, String titulo, String cuerpo,
                                  String entidadTipo, Long entidadId) {
        try {
            TenantContext.set(tndId.toString());
            tenantSupport.applyTenant(em);

            Long id = ((Number) em.createNativeQuery("""
                    INSERT INTO notificaciones
                        (ntf_usr_id, ntf_tipo, ntf_titulo, ntf_cuerpo, ntf_entidad_tipo, ntf_entidad_id)
                    VALUES (:usrId, CAST(:tipo AS tipo_notificacion), :titulo, :cuerpo, :entidadTipo, :entidadId)
                    RETURNING ntf_id
                    """)
                    .setParameter("usrId", usrId)
                    .setParameter("tipo", tipo)
                    .setParameter("titulo", titulo)
                    .setParameter("cuerpo", cuerpo)
                    .setParameter("entidadTipo", entidadTipo)
                    .setParameter("entidadId", entidadId)
                    .getSingleResult()).longValue();

            messagingTemplate.convertAndSend("/topic/cliente/" + tndId + "/" + usrId, Map.of(
                    "id", id,
                    "tipo", tipo,
                    "titulo", titulo,
                    "mensaje", cuerpo == null ? "" : cuerpo,
                    "entidad_tipo", entidadTipo == null ? "" : entidadTipo,
                    "entidad_id", entidadId,
                    "leida", false,
                    "creado_en", OffsetDateTime.now()
            ));
        } catch (Exception e) {
            log.error("[Notificaciones] No se pudo notificar al cliente {} de la tienda {}: {}",
                    usrId, tndId, e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    /** Aviso por correo (Resend) al email que el admin configuró en Ajustes cuando un pedido se
     *  paga — además de la notificación in-app de notificarAdmin(). Si la tienda no tiene un
     *  correo configurado, no se envía nada. */
    @Transactional(readOnly = true)
    public void avisarNuevoPedidoPorEmail(Long tndId, Long pedId, String numero) {
        try {
            TenantContext.set(tndId.toString());
            tenantSupport.applyTenant(em);

            String emailDestino = tiendaRepo.findById(tndId)
                    .map(t -> t.getEmailNotificacionPedidos())
                    .orElse(null);
            if (emailDestino == null || emailDestino.isBlank()) return;

            Object[] row = (Object[]) em.createNativeQuery("""
                    SELECT p.ped_total_centavos, COALESCE(cp.cp_nombre, u.usr_email)
                    FROM pedidos p
                    JOIN usuarios u ON u.usr_id = p.ped_usr_id
                    LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = u.usr_id
                    WHERE p.ped_id = :pedId
                    """)
                    .setParameter("pedId", pedId)
                    .getSingleResult();
            long totalPesos = ((Number) row[0]).longValue() / 100L;
            String clienteNombre = (String) row[1];

            emailService.sendNuevoPedido(tndId, emailDestino, numero, clienteNombre, totalPesos);
        } catch (Exception e) {
            log.warn("[Notificaciones] No se pudo enviar aviso por correo del pedido {} en tienda {}: {}",
                    numero, tndId, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    /** Confirmación transaccional al comprador cuando su pedido se paga (RF-031/F-08 de la
     *  auditoría) — resumen congelado (ítems, dirección si aplica, método, total). Se dispara
     *  desde el mismo evento que ya usa avisarNuevoPedidoPorEmail (PedidoPagadoEvent), que solo
     *  se publica una vez por pedido, así que esta confirmación queda naturalmente idempotente. */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public void avisarConfirmacionCompraAlCliente(Long tndId, Long pedId, String numero) {
        try {
            TenantContext.set(tndId.toString());
            tenantSupport.applyTenant(em);

            Object[] pedidoRow;
            try {
                pedidoRow = (Object[]) em.createNativeQuery("""
                        SELECT u.usr_email, COALESCE(cp.cp_nombre, u.usr_email),
                               p.ped_dir_snapshot::text, p.ped_total_centavos
                        FROM pedidos p
                        JOIN usuarios u ON u.usr_id = p.ped_usr_id
                        LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = u.usr_id
                        WHERE p.ped_id = :pedId
                        """)
                        .setParameter("pedId", pedId)
                        .getSingleResult();
            } catch (jakarta.persistence.NoResultException e) {
                return;
            }
            String email = (String) pedidoRow[0];
            if (email == null || email.isBlank()) return;
            String nombre = (String) pedidoRow[1];
            Map<String, Object> direccion = parseDireccion((String) pedidoRow[2]);
            long totalPesos = ((Number) pedidoRow[3]).longValue() / 100L;

            List<Object[]> itemRows = em.createNativeQuery("""
                    SELECT pi_nombre_snap, pi_cantidad, pi_precio_unitario_centavos
                    FROM pedido_items WHERE pi_ped_id = :pedId ORDER BY pi_id ASC
                    """)
                    .setParameter("pedId", pedId)
                    .getResultList();
            List<ResendEmailService.ItemResumenEmail> items = new ArrayList<>();
            for (Object[] row : itemRows) {
                items.add(new ResendEmailService.ItemResumenEmail(
                        (String) row[0], ((Number) row[1]).intValue(), ((Number) row[2]).longValue() / 100L));
            }

            String metodoPago = null;
            try {
                metodoPago = (String) em.createNativeQuery("""
                        SELECT pag_metodo::text FROM pagos
                        WHERE pag_ped_id = :pedId AND pag_estado = CAST('APPROVED' AS estado_pago)
                        ORDER BY pag_id DESC LIMIT 1
                        """)
                        .setParameter("pedId", pedId)
                        .getSingleResult();
            } catch (jakarta.persistence.NoResultException e) {
                // sin pago aprobado registrado (no debería pasar si ya llegamos a "pagado") — se
                // envía igual el correo, solo sin la línea de método de pago.
            }
            String metodoPagoLabel = metodoPago != null ? METODO_PAGO_LABEL.getOrDefault(metodoPago, metodoPago) : null;

            emailService.sendConfirmacionCompra(tndId, email, nombre, numero, items, direccion, metodoPagoLabel, totalPesos);
        } catch (Exception e) {
            log.warn("[Notificaciones] No se pudo enviar la confirmación de compra del pedido {} en tienda {}: {}",
                    numero, tndId, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private Map<String, Object> parseDireccion(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Notifica a los clientes que tienen este producto en su lista de deseos. */
    @SuppressWarnings("unchecked")
    @Transactional
    public void notificarInteresadosEnProducto(Long tndId, Long prdId, String nombreProducto) {
        List<Long> usrIds;
        try {
            TenantContext.set(tndId.toString());
            tenantSupport.applyTenant(em);
            usrIds = em.createNativeQuery("SELECT ld_usr_id FROM lista_deseos WHERE ld_prd_id = :prdId")
                    .setParameter("prdId", prdId)
                    .getResultList()
                    .stream().map(r -> ((Number) r).longValue()).toList();
        } catch (Exception e) {
            log.error("[Notificaciones] No se pudo consultar la lista de deseos del producto {} en tienda {}: {}",
                    prdId, tndId, e.getMessage(), e);
            return;
        } finally {
            TenantContext.clear();
        }

        for (Long usrId : usrIds) {
            notificarCliente(tndId, usrId, "stock_disponible", "¡Ya disponible!",
                    nombreProducto + " ya tiene stock disponible.", "producto", prdId);
        }
    }

    /** Notifica a todos los clientes activos de la tienda (p.ej. al publicar una promoción). */
    @SuppressWarnings("unchecked")
    @Transactional
    public void notificarOfertaATodos(Long tndId, String titulo, String cuerpo, String entidadTipo, Long entidadId) {
        List<Long> usrIds;
        try {
            TenantContext.set(tndId.toString());
            tenantSupport.applyTenant(em);
            // Solo a quienes no rechazaron promociones (F-07 de la auditoría) — antes se le
            // avisaba una oferta a todo activo sin excepción, sin importar su preferencia.
            // COALESCE trata "sin fila de perfil" como el DEFAULT real de la columna (true).
            usrIds = em.createNativeQuery("""
                    SELECT u.usr_id FROM usuarios u
                    LEFT JOIN clientes_perfil cp ON cp.cp_usr_id = u.usr_id
                    WHERE u.usr_tnd_id = :tndId AND u.usr_activo = true
                      AND COALESCE(cp.cp_acepta_promo, true) = true
                    """)
                    .setParameter("tndId", tndId)
                    .getResultList()
                    .stream().map(r -> ((Number) r).longValue()).toList();
        } catch (Exception e) {
            log.error("[Notificaciones] No se pudo consultar los clientes de la tienda {}: {}",
                    tndId, e.getMessage(), e);
            return;
        } finally {
            TenantContext.clear();
        }

        for (Long usrId : usrIds) {
            notificarCliente(tndId, usrId, "oferta", titulo, cuerpo, entidadTipo, entidadId);
        }
    }
}
