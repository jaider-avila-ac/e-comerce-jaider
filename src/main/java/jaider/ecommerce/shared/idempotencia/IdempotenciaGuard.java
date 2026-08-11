package jaider.ecommerce.shared.idempotencia;

import com.fasterxml.jackson.databind.ObjectMapper;
import jaider.ecommerce.shared.idempotencia.IdempotenciaService.Registro;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Envuelve cualquier operación transaccional (checkout, cobro, venta local, etc.) para que la
 * misma "intención" — identificada por una Idempotency-Key que el cliente genera y conserva
 * hasta recibir un resultado definitivo — produzca EXACTAMENTE un efecto de negocio sin importar
 * cuántas veces se reciba la solicitud (doble clic, reintento por timeout, reenvío de proxy).
 * Ver REAUDITORIA_FUNCIONAL_E_IDEMPOTENCIA.md sección 5.1.
 *
 * Bean separado de IdempotenciaService a propósito: así las llamadas a intentarCrear/
 * buscarPorClave/completar/liberar pasan siempre por el proxy de Spring y sus @Transactional
 * (REQUIRES_NEW en particular) se respetan — auto-invocarlos desde el mismo bean no funcionaría.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotenciaGuard {

    // Si una fila lleva más de esto en 'procesando', se asume que el proceso que la creó murió
    // a medias (crash, timeout de red hacia Wompi, etc.) y se permite reclamarla para reintentar
    // — evita que una clave quede bloqueada para siempre por una falla real del servidor.
    private static final Duration TIMEOUT_PROCESANDO = Duration.ofSeconds(90);

    private final IdempotenciaService idempotenciaService;
    private final ObjectMapper objectMapper;

    /**
     * @param tndId, usrId  dueño de la operación (por tenant y por usuario — la misma clave de
     *                      otro usuario no colisiona).
     * @param operacion     nombre corto y estable del tipo de operación (ej. "checkout_hospedado").
     * @param clave         la Idempotency-Key que mandó el cliente.
     * @param requestBody   se hashea para detectar "misma clave, cuerpo distinto" (error del
     *                      cliente reusando una clave para una intención distinta).
     * @param responseType  clase concreta de la respuesta, para reconstruirla desde JSON en un replay.
     * @param logica        la operación real — se ejecuta como mucho UNA vez por clave.
     */
    public <T> T ejecutar(Long tndId, Long usrId, String operacion, String clave, Object requestBody,
                           Class<T> responseType, Supplier<T> logica) {
        if (clave == null || clave.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el encabezado Idempotency-Key");
        }
        String hash = hash(requestBody);

        Long idmId;
        try {
            idmId = idempotenciaService.intentarCrear(tndId, usrId, operacion, clave, hash);
        } catch (ClaveDuplicadaException e) {
            Registro reg = idempotenciaService.buscarPorClave(tndId, usrId, operacion, clave);
            if (reg == null) {
                // Fila desapareció entre el INSERT fallido y esta consulta (no debería pasar) —
                // pedir al cliente que reintente con una clave nueva en vez de dejarlo colgado.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se pudo procesar la solicitud, intenta de nuevo.");
            }
            if (!reg.requestHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Esta clave de operación ya se usó con datos diferentes.");
            }
            if ("completado".equals(reg.estado())) {
                // Misma intención, ya resuelta antes — se devuelve el mismo resultado sin volver
                // a ejecutar nada. Esto es lo que hace que 20 reintentos produzcan un solo efecto.
                return fromJson(reg.respuestaJson(), responseType);
            }
            // 'procesando'
            if (reg.actualizadoEn().isBefore(OffsetDateTime.now().minusSeconds(TIMEOUT_PROCESANDO.toSeconds()))) {
                idempotenciaService.reclamar(reg.idmId());
                idmId = reg.idmId();
            } else {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya estamos procesando esta solicitud, espera un momento antes de reintentar.");
            }
        }

        T resultado;
        try {
            resultado = logica.get();
        } catch (RuntimeException e) {
            // La operación real no llegó a producir un efecto de negocio persistente (contrato:
            // solo se libera desde puntos donde eso está garantizado — ver comentarios en cada
            // llamador) — se libera la clave para que el cliente corrija el problema (ej. carrito
            // vacío, tarjeta inválida) y reintente con la MISMA clave sin esperar el timeout.
            idempotenciaService.liberar(idmId);
            throw e;
        }

        idempotenciaService.completar(idmId, toJson(resultado), extraerPedId(resultado));
        return resultado;
    }

    private String hash(Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular el hash de idempotencia", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Idempotencia] No se pudo serializar la respuesta para cachearla: {}", e.getMessage());
            return "{}";
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo reconstruir la respuesta cacheada de idempotencia", e);
        }
    }

    /** Si la respuesta trae un campo pedId/pedidoId numérico, lo guarda en idm_ped_id (solo
     *  informativo, para poder auditar qué pedido generó cada clave). Best-effort: si el tipo de
     *  respuesta no lo tiene, no pasa nada. */
    private Long extraerPedId(Object resultado) {
        try {
            for (var c : resultado.getClass().getRecordComponents()) {
                if (("pedId".equals(c.getName()) || "pedidoId".equals(c.getName())) && c.getType() == Long.class) {
                    return (Long) c.getAccessor().invoke(resultado);
                }
            }
        } catch (Exception ignored) {
            // best-effort, no crítico
        }
        return null;
    }
}