package jaider.ecommerce.shared;

import jaider.ecommerce.tienda.TiendaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

// Endpoint público de solo lectura para verificar que el backend, la base de datos y Redis
// responden tras un deploy. No recibe parámetros ni hace escrituras, y nunca devuelve mensajes
// de excepción crudos al cliente — solo UP/DOWN — para no filtrar detalles internos.
@Slf4j
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final TiendaRepository tiendaRepository;
    private final RedisConnectionFactory redisConnectionFactory;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("timestamp", OffsetDateTime.now().toString());

        try {
            long tiendas = tiendaRepository.count();
            status.put("database", Map.of("status", "UP", "tiendas", tiendas));
        } catch (Exception e) {
            log.warn("[HEALTH] fallo verificando PostgreSQL", e);
            status.put("database", Map.of("status", "DOWN"));
        }

        try {
            String pong = redisConnectionFactory.getConnection().ping();
            status.put("redis", Map.of("status", "UP", "ping", pong));
        } catch (Exception e) {
            log.warn("[HEALTH] fallo verificando Redis", e);
            status.put("redis", Map.of("status", "DOWN"));
        }

        return ResponseEntity.ok(status);
    }
}
