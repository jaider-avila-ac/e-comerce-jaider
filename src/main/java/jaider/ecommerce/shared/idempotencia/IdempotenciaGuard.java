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
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Envuelve cualquier operación transaccional (checkout, cobro, venta local, etc.) para que la
 * misma "intención" — identificada por una Idempotency-Key que el cliente genera y conserva
 * hasta recibir un resultado definitivo — produzca EXACTAMENTE un efecto de negocio sin importar
 * cuántas veces se reciba la solicitud (doble clic, reintento por timeout, reenvío de proxy,
 * respuesta perdida, recarga de página).
 *
 * Ver REAUDITORIA_FUNCIONAL_E_IDEMPOTENCIA.md sección 5.1 y, sobre todo,
 * TERCERA_AUDITORIA_FUNCIONAL_E_IDEMPOTENCIA.md — esta clase corrige específicamente:
 *   I-03: la reclamación de una clave abandonada ya no es un UPDATE incondicional (dos
 *         reclamantes podían "ganar" a la vez) — ahora usa reclamarAtomico(), un compare-and-set
 *         real que solo uno puede ganar.
 *   I-05: antes de re-ejecutar una operación reclamada, SIEMPRE se intenta reconciliar primero
 *         (parámetro `reconciliar`) consultando el estado real ya persistido — nunca se re-ejecuta
 *         a ciegas una operación cuyo efecto externo (ej. cobro a Wompi) es desconocido.
 *   I-08: la clave se valida como UUID de formato estándar antes de usarla.
 *
 * Bean separado de IdempotenciaService a propósito: así las llamadas a intentarCrear/
 * buscarPorClave/completar/reclamarAtomico/liberar pasan siempre por el proxy de Spring y sus
 * @Transactional (REQUIRES_NEW en particular) se respetan — auto-invocarlos desde el mismo bean
 * no funcionaría.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotenciaGuard {

    private static final Pattern FORMATO_CLAVE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final IdempotenciaService idempotenciaService;
    private final ObjectMapper objectMapper;

    /**
     * @param tndId, usrId  dueño de la operación (por tenant y por usuario — la misma clave de
     *                      otro usuario no colisiona).
     * @param operacion     nombre corto y estable del tipo de operación (ej. "checkout_hospedado").
     * @param clave         la Idempotency-Key que mandó el cliente — debe ser un UUID.
     * @param hashBasis     objeto a serializar y hashear para detectar "misma clave, cuerpo
     *                      distinto". A propósito NO es siempre el request completo — para
     *                      operaciones con credenciales efímeras (ej. token de tarjeta, que
     *                      cambia en cada retokenización de la MISMA tarjeta), el llamador debe
     *                      pasar solo los campos que identifican la intención real (ver I-06).
     * @param responseType  clase concreta de la respuesta, para reconstruirla desde JSON en un replay.
     * @param reconciliar   dado el registro existente (con su idm_ped_id, si ya se alcanzó a
     *                      crear), intenta reconstruir un resultado definitivo consultando el
     *                      estado real ya persistido (BD y, si aplica, el gateway) — SIN volver a
     *                      ejecutar ningún efecto. Se llama siempre antes de reclamar una
     *                      operación abandonada. Si devuelve Optional.empty() y ya hay
     *                      idm_ped_id (efectos persistentes conocidos), NO se re-ejecuta la
     *                      lógica — se rechaza pidiendo conciliación manual, porque re-ejecutar
     *                      podría repetir un cobro cuyo resultado real se desconoce.
     * @param logica        la operación real — se ejecuta como mucho UNA vez por clave. Recibe el
     *                      id de la fila de idempotencia para que el llamador pueda registrar el
     *                      pedido/pago apenas se creen (ver IdempotenciaService.registrarPedido),
     *                      antes de intentar cualquier cobro.
     */
    public <T> T ejecutar(Long tndId, Long usrId, String operacion, String clave, Object hashBasis,
                           Class<T> responseType, Function<Registro, Optional<T>> reconciliar,
                           Function<Long, T> logica) {
        if (clave == null || clave.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el encabezado Idempotency-Key");
        }
        if (!FORMATO_CLAVE.matcher(clave.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key inválida — debe ser un UUID (ej. generado con crypto.randomUUID())");
        }
        String hash = hash(hashBasis);

        Long idmId;
        try {
            idmId = idempotenciaService.intentarCrear(tndId, usrId, operacion, clave, hash);
        } catch (ClaveDuplicadaException e) {
            Registro reg = idempotenciaService.buscarPorClave(tndId, usrId, operacion, clave);
            if (reg == null) {
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

            // 'procesando' — I-03: reclamación por compare-and-set real, no un UPDATE incondicional.
            if (!reg.leaseVencido()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya estamos procesando esta solicitud, espera un momento antes de reintentar.");
            }
            if (!idempotenciaService.reclamarAtomico(reg.idmId())) {
                // Otro proceso reclamó la fila una fracción de segundo antes — no soy el dueño.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya estamos procesando esta solicitud, espera un momento antes de reintentar.");
            }

            // I-05: gané la reclamación, pero antes de re-ejecutar CUALQUIER lógica, intento
            // reconciliar consultando el estado real ya persistido.
            Optional<T> conciliado = reconciliar.apply(reg);
            if (conciliado.isPresent()) {
                idempotenciaService.completar(reg.idmId(), toJson(conciliado.get()));
                return conciliado.get();
            }
            if (reg.pedId() != null) {
                // Ya hay efectos persistentes (pedido/pago creados) pero no se pudo reconstruir
                // un resultado definitivo — NO es seguro re-ejecutar (podría repetir un cobro
                // cuyo resultado real todavía no se conoce). Requiere conciliación manual.
                log.error("[Idempotencia] Operación {} (clave {}) reclamada con idm_ped_id={} pero sin poder " +
                        "reconciliar un resultado definitivo — se bloquea el reintento automático.",
                        operacion, clave, reg.pedId());
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No pudimos confirmar el resultado de tu intento anterior. Contáctanos con el número " +
                        "de pedido más reciente antes de volver a intentarlo.");
            }
            // Sin efectos persistentes conocidos (murió antes de crear nada) — seguro re-ejecutar.
            idmId = reg.idmId();
        }

        T resultado;
        try {
            resultado = logica.apply(idmId);
        } catch (RuntimeException e) {
            // La operación real no llegó a producir un efecto de negocio persistente (contrato:
            // solo se libera desde puntos donde eso está garantizado — ver comentarios en cada
            // llamador) — se libera la clave para que el cliente corrija el problema (ej. carrito
            // vacío, tarjeta inválida) y reintente con la MISMA clave sin esperar el timeout.
            idempotenciaService.liberar(idmId);
            throw e;
        }

        idempotenciaService.completar(idmId, toJson(resultado));
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
}