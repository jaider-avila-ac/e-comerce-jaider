package jaider.ecommerce.notificacion;

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

            emailService.sendNuevoPedido(emailDestino, numero, clienteNombre, totalPesos);
        } catch (Exception e) {
            log.warn("[Notificaciones] No se pudo enviar aviso por correo del pedido {} en tienda {}: {}",
                    numero, tndId, e.getMessage());
        } finally {
            TenantContext.clear();
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
            usrIds = em.createNativeQuery(
                    "SELECT usr_id FROM usuarios WHERE usr_tnd_id = :tndId AND usr_activo = true")
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
