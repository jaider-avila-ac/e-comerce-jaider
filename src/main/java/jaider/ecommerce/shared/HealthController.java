package jaider.ecommerce.shared;

import jaider.ecommerce.tienda.TiendaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final TiendaRepository tiendaRepository;
    private final StringRedisTemplate redisTemplate;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("timestamp", OffsetDateTime.now().toString());

        // Verificar PostgreSQL
        try {
            long tiendas = tiendaRepository.count();
            status.put("database", Map.of("status", "UP", "tiendas", tiendas));
        } catch (Exception e) {
            status.put("database", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        // Verificar Redis
        try {
            redisTemplate.opsForValue().set("health:ping", "pong");
            Object pong = redisTemplate.opsForValue().get("health:ping");
            status.put("redis", Map.of("status", "UP", "ping", pong));
        } catch (Exception e) {
            status.put("redis", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        return ResponseEntity.ok(status);
    }
}
