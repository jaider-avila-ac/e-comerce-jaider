package jaider.ecommerce.tienda.envio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jaider.ecommerce.notificacion.event.PedidoEstadoCambiadoEvent;
import jaider.ecommerce.pedido.Pedido;
import jaider.ecommerce.pedido.PedidoRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.tienda.integracion.EnviaCredentials;
import jaider.ecommerce.tienda.integracion.TenantIntegrationResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Webhook de seguimiento de Envia.com — PLAN_INTEGRACION_ENVIA.md, Fase 5. Pedido explícito del
 * usuario: solo actualiza el pedido cuando Envia confirma que fue ENTREGADO o DEVUELTO (no
 * intenta replicar todo el flujo de estados que ya maneja el staff en PedidoService — "enviado"
 * sigue siendo una decisión del admin, con sus propias validaciones).
 *
 * A diferencia de los webhooks de Wompi (una sola URL para todas las tiendas, tenant resuelto
 * desde el contenido firmado), esta URL lleva el {@code tndId} en la propia ruta — la
 * documentación de Envia no deja claro un formato único de payload (varios "tipos" de webhook
 * posibles, con o sin datos de referencia propia), así que resolver el tenant desde la URL es
 * más simple y no depende de adivinar esa parte. Ver {@code EnvioWebhookController} y
 * {@code SuperadminTiendaService.detalle()} (webhookEnviaUrl).
 *
 * Nunca lanza por un evento que no reconoce o un pedido que no encuentra — un webhook responde
 * 200 siempre que la autenticación sea válida, para que Envia no reintente indefinidamente algo
 * que nunca vamos a poder procesar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvioWebhookService {

    private final TenantSupport tenantSupport;
    private final TenantIntegrationResolver integrationResolver;
    private final PedidoRepository pedidoRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void procesar(Long tndId, Map<String, Object> body, String authHeader) {
        EnviaCredentials creds;
        try {
            creds = integrationResolver.envioCredentials(tndId);
        } catch (IllegalStateException e) {
            log.warn("[EnvioWebhook] tenant={} sin credenciales de Envia configuradas — evento ignorado", tndId);
            return;
        }

        // Corrección de auditoría (2026-09-01): antes esta verificación era opcional — si la
        // tienda no tenía webhook_secret configurado, CUALQUIERA que supiera el tnd_id (visible
        // en la URL) y un código de rastreo real podía llamar este endpoint y marcar un pedido
        // como entregado/devuelto sin autenticarse. Y si el secreto SÍ estaba configurado pero no
        // coincidía, el servicio simplemente retornaba y el controller igual respondía 200 — o
        // sea que ni siquiera se notaba el rechazo. Ahora el secreto es OBLIGATORIO para poder
        // procesar cualquier evento, y un secreto ausente/incorrecto lanza 401 de verdad (Spring
        // lo traduce automáticamente en el controller) en vez de un 200 silencioso.
        String secreto = creds.webhookSecret();
        if (secreto == null || secreto.isBlank()) {
            log.error("[EnvioWebhook] tenant={} no tiene webhook_secret configurado — evento rechazado (configúralo antes de usar el webhook)", tndId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Esta tienda no tiene configurado el secreto del webhook de Envia");
        }
        // Comparación en tiempo constante — evita filtrar por timing cuánto del secreto coincide.
        if (authHeader == null || !constantTimeEquals("Bearer " + secreto, authHeader)) {
            log.warn("[EnvioWebhook] tenant={} — token inválido, evento rechazado", tndId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido");
        }

        JsonNode node = objectMapper.valueToTree(body);
        String trackingNumber = primero(node, "trackingNumber", "tracking_number");
        String statusCrudo = primero(node, "status");
        if (trackingNumber == null || trackingNumber.isBlank() || statusCrudo == null) {
            log.info("[EnvioWebhook] tenant={} — payload sin trackingNumber/status reconocible, ignorado: {}", tndId, body);
            return;
        }

        String estadoMapeado = mapearEstado(statusCrudo);
        if (estadoMapeado == null) {
            log.info("[EnvioWebhook] tenant={} tracking={} status='{}' — no es entregado/devuelto, no se actualiza nada",
                    tndId, trackingNumber, statusCrudo);
            return;
        }

        TenantContext.set(tndId.toString());
        tenantSupport.requireTenant(em);

        Pedido pedido = pedidoRepo.findByTndIdAndCodigoRastreo(tndId, trackingNumber).orElse(null);
        if (pedido == null) {
            log.info("[EnvioWebhook] tenant={} tracking={} no corresponde a ningún pedido conocido — ignorado", tndId, trackingNumber);
            return;
        }

        int actualizadas = pedidoRepo.avanzarEstadoPorWebhookEnvia(pedido.getId(), estadoMapeado);
        if (actualizadas == 0) {
            log.info("[EnvioWebhook] pedido={} ya estaba en un estado final — webhook duplicado o tardío, ignorado", pedido.getId());
            return;
        }

        em.createNativeQuery("""
                INSERT INTO pedido_historial_estados (phe_ped_id, phe_estado, phe_admin_id, phe_nota)
                VALUES (:pedId, CAST(:estado AS estado_pedido), NULL, :nota)
                """)
                .setParameter("pedId", pedido.getId())
                .setParameter("estado", estadoMapeado)
                .setParameter("nota", "Actualizado automáticamente por webhook de Envia (tracking " + trackingNumber + ")")
                .executeUpdate();

        log.info("[EnvioWebhook] pedido={} tenant={} tracking={} -> estado={}",
                pedido.getId(), tndId, trackingNumber, estadoMapeado);
        eventPublisher.publishEvent(new PedidoEstadoCambiadoEvent(
                tndId, pedido.getUsrId(), pedido.getId(), pedido.getNumero(), estadoMapeado));
    }

    /** Envia documenta ~28 estados posibles (Created, Picked Up, In Transit, Out for Delivery,
     *  Delivered, Damaged, Delayed, Lost, Undelivered, Delivery exception, Returned, etc.) — a
     *  propósito solo se actúa sobre los dos que pidió el usuario explícitamente. Los demás
     *  quedan solo logueados: no forzar "enviado" desde acá evita pisar el flujo de staff que ya
     *  lo maneja (PedidoService).
     *
     *  Corrección de auditoría (2026-09-01): la versión anterior usaba
     *  {@code contains("deliver")}, que también matchea "Undelivered" y "Delivery exception" —
     *  ambos serían justo lo CONTRARIO de una entrega exitosa, y el bug los marcaba como
     *  "entregado". Ahora es una comparación exacta contra una lista cerrada de valores
     *  conocidos, no una subcadena — cualquier variante que Envia use y no esté en esta lista
     *  simplemente no se reconoce (se loguea, no se actúa), en vez de adivinar por contenido. */
    private static final java.util.Set<String> ESTADOS_ENTREGADO = java.util.Set.of(
            "delivered", "entregado");
    private static final java.util.Set<String> ESTADOS_DEVUELTO = java.util.Set.of(
            "returned", "returned to sender", "return to sender", "devuelto");

    private String mapearEstado(String statusCrudo) {
        String s = statusCrudo.trim().toLowerCase();
        if (ESTADOS_ENTREGADO.contains(s)) return "entregado";
        if (ESTADOS_DEVUELTO.contains(s)) return "devuelto";
        return null;
    }

    /** Comparación de tiempo constante (MessageDigest.isEqual está diseñado para esto) — un
     *  == de String normal terminaría la comparación en el primer carácter distinto, y ese
     *  tiempo de respuesta ligeramente distinto es, en teoría, una fuga de información que
     *  permitiría adivinar el secreto carácter por carácter. */
    private boolean constantTimeEquals(String esperado, String recibido) {
        return MessageDigest.isEqual(
                esperado.getBytes(StandardCharsets.UTF_8),
                recibido.getBytes(StandardCharsets.UTF_8));
    }

    /** Acepta tanto el formato v1 (plano) como el v2 (anidado en "data") de Envia — ver el
     *  javadoc de la clase: la documentación no confirma cuál llega según cómo se registre el
     *  webhook, así que se revisan ambas formas en vez de asumir una sola. */
    private String primero(JsonNode node, String... campos) {
        for (String campo : campos) {
            String v = node.path(campo).asText(null);
            if (v != null) return v;
            v = node.path("data").path(campo).asText(null);
            if (v != null) return v;
        }
        return null;
    }
}
