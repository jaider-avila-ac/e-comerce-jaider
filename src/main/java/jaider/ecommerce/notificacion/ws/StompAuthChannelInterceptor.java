package jaider.ecommerce.notificacion.ws;

import jaider.ecommerce.auth.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Autentica el CONNECT de STOMP con el mismo JWT que usa el resto de la API (no hay sesión HTTP:
 * SockJS/WebSocket no permite headers custom en el handshake HTTP, así que el token viaja en el
 * header nativo "Authorization" del frame CONNECT). Además valida en cada SUBSCRIBE que el
 * destino pedido corresponda al tenant/usuario del token — sin esto, cualquier cliente conectado
 * podría suscribirse al topic de otra tienda o de otro cliente.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    // rol_empleado (Postgres) usa valores en minúscula: superadmin, admin, colaborador, bodega.
    private static final Set<String> ROLES_ADMIN = Set.of("superadmin", "admin", "colaborador", "bodega");

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            handleSubscribe(accessor);
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (token == null || !jwtService.isValid(token)) {
            throw new MessagingException("Token inválido o ausente");
        }

        Long tndId = jwtService.extractTndId(token);
        if (tndId == null) {
            throw new MessagingException("Token sin tienda");
        }
        String role = jwtService.extractRole(token);
        Long usrId = jwtService.extractUsrId(token);

        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) {
            throw new MessagingException("Sesión WebSocket inválida");
        }
        // Los atributos de sesión STOMP son un ConcurrentHashMap: no admite valores null
        // (un admin no tiene usr_id, por ejemplo), así que solo se guardan los presentes.
        if (role != null) attrs.put("role", role);
        attrs.put("tndId", tndId);
        if (usrId != null) attrs.put("usrId", usrId);
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (destination == null || attrs == null) {
            throw new MessagingException("Suscripción inválida");
        }

        String role = (String) attrs.get("role");
        Long tndId = (Long) attrs.get("tndId");
        Long usrId = (Long) attrs.get("usrId");

        if (destination.startsWith("/topic/admin/")) {
            if (role == null || !ROLES_ADMIN.contains(role.toLowerCase())) {
                throw new MessagingException("No autorizado para notificaciones de admin");
            }
            if (!destination.equals("/topic/admin/" + tndId)) {
                throw new MessagingException("No autorizado para esta tienda");
            }
        } else if (destination.startsWith("/topic/cliente/")) {
            if (usrId == null) {
                throw new MessagingException("No autorizado para notificaciones de cliente");
            }
            if (!destination.equals("/topic/cliente/" + tndId + "/" + usrId)) {
                throw new MessagingException("No autorizado para este cliente");
            }
        } else {
            throw new MessagingException("Destino no permitido: " + destination);
        }
    }

    private String extractToken(StompHeaderAccessor accessor) {
        String auth = accessor.getFirstNativeHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return accessor.getFirstNativeHeader("token");
    }
}
