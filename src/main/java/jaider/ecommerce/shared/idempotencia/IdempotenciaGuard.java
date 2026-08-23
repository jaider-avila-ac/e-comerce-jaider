package jaider.ecommerce.shared.idempotencia;

import com.fasterxml.jackson.databind.ObjectMapper;
import jaider.ecommerce.shared.idempotencia.IdempotenciaService.Registro;
import jaider.ecommerce.shared.idempotencia.IdempotenciaService.Titular;
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
 * respuesta perdida, recarga de página, dos pestañas).
 *
 * Ver REAUDITORIA_FUNCIONAL_E_IDEMPOTENCIA.md sección 5.1, TERCERA_AUDITORIA (I-02 a I-08) y
 * CUARTA_AUDITORIA (Q-01 a Q-08) — esta clase corrige específicamente:
 *   I-03/Q-07: reclamar una clave abandonada usa reclamarAtomico() (compare-and-set real) y cada
 *              reclamo genera un token de propietario nuevo — completar()/liberar() solo actúan
 *              si el token coincide, así una finalización tardía de un proceso "zombie" no puede
 *              pisar el trabajo de quien ya reclamó la fila después.
 *   I-05:      antes de re-ejecutar una operación reclamada, SIEMPRE se intenta reconciliar
 *              primero consultando el estado real ya persistido — nunca a ciegas.
 *   I-08:      la clave se valida como UUID de formato estándar antes de usarla.
 *   Q-06:      si falla serializar la respuesta, la operación NO se marca "completado" con una
 *              respuesta rota — la llamada actual igual recibe el resultado correcto.
 *   Q-08:      además de la clave exacta, se busca cualquier operación activa con la MISMA
 *              intención (mismo hash) — cubre dos pestañas generando claves distintas para la
 *              misma compra.
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

    /** Resultado interno de resolver una fila ya existente: o ya hay un valor definitivo para
     *  devolver de inmediato, o se ganó el derecho de (re)ejecutar la lógica con este titular. */
    private sealed interface Resolucion<T> {
        record Resuelta<T>(T valor) implements Resolucion<T> {}
        record Continuar<T>(Titular titular) implements Resolucion<T> {}
    }

    /**
     * @param tndId, usrId  dueño de la operación (por tenant y por usuario — la misma clave de
     *                      otro usuario no colisiona).
     * @param operacion     nombre corto y estable del tipo de operación (ej. "checkout_hospedado").
     * @param clave         la Idempotency-Key que mandó el cliente — debe ser un UUID.
     * @param hashBasis     objeto a serializar y hashear para detectar "misma clave, cuerpo
     *                      distinto" y para el chequeo de intención activa (Q-08). A propósito NO
     *                      es siempre el request completo — para operaciones con credenciales
     *                      efímeras (ej. token de tarjeta), el llamador debe pasar solo los campos
     *                      que identifican la intención real (ver I-06), y para checkout debe
     *                      incluir el contenido/total real del carrito (ver Q-04), no solo el DTO.
     * @param responseType  clase concreta de la respuesta, para reconstruirla desde JSON en un replay.
     * @param reconciliar   dado el registro existente (con su idm_ped_id, si ya se alcanzó a
     *                      crear), intenta reconstruir un resultado definitivo consultando el
     *                      estado real ya persistido (BD y, si aplica, el gateway) — SIN volver a
     *                      ejecutar ningún efecto. Se llama siempre antes de reclamar una
     *                      operación abandonada. Si devuelve Optional.empty() y ya hay
     *                      idm_ped_id (efectos persistentes conocidos), NO se re-ejecuta la
     *                      lógica — se rechaza pidiendo conciliación manual, porque re-ejecutar
     *                      podría repetir un cobro cuyo resultado real se desconoce.
     * @param logica        la operación real — se ejecuta como mucho UNA vez por clave/intención.
     *                      Recibe el id de la fila de idempotencia para que el llamador pueda
     *                      registrar el pedido/pago apenas se creen, antes de intentar cualquier
     *                      cobro.
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

        // Q-08: antes de tocar la clave literal, ¿ya hay una operación activa con esta MISMA
        // intención (mismo hash), sin importar qué clave usó? Cubre dos pestañas del mismo
        // usuario comprando exactamente lo mismo con claves distintas.
        Titular titular;
        Optional<Registro> porHash = idempotenciaService.buscarActivaPorHash(tndId, usrId, operacion, hash);
        if (porHash.isPresent()) {
            Resolucion<T> res = resolverExistente(porHash.get(), hash, operacion, clave, responseType, reconciliar);
            if (res instanceof Resolucion.Resuelta<T> r) return r.valor();
            titular = ((Resolucion.Continuar<T>) res).titular();
        } else {
            try {
                titular = idempotenciaService.intentarCrear(tndId, usrId, operacion, clave, hash);
            } catch (ClaveDuplicadaException e) {
                Registro reg = idempotenciaService.buscarPorClave(tndId, usrId, operacion, clave);
                if (reg == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "No se pudo procesar la solicitud, intenta de nuevo.");
                }
                Resolucion<T> res = resolverExistente(reg, hash, operacion, clave, responseType, reconciliar);
                if (res instanceof Resolucion.Resuelta<T> r) return r.valor();
                titular = ((Resolucion.Continuar<T>) res).titular();
            }
        }

        T resultado;
        try {
            resultado = logica.apply(titular.idmId());
        } catch (RuntimeException e) {
            // La operación real no llegó a producir un efecto de negocio persistente (contrato:
            // solo se libera desde puntos donde eso está garantizado — ver comentarios en cada
            // llamador) — se libera la clave para que el cliente corrija el problema (ej. carrito
            // vacío, tarjeta inválida) y reintente con la MISMA clave sin esperar el timeout.
            idempotenciaService.liberar(titular.idmId(), titular.owner());
            throw e;
        }

        completarSiSePuede(titular, resultado);
        return resultado;
    }

    /** Decide qué hacer con una fila ya existente (encontrada por clave exacta o por hash de
     *  intención): devolver un resultado definitivo, rechazar, o ganar el derecho de continuar. */
    private <T> Resolucion<T> resolverExistente(Registro reg, String hashNuevo, String operacion, String clave,
                                                 Class<T> responseType, Function<Registro, Optional<T>> reconciliar) {
        if (!reg.requestHash().equals(hashNuevo)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta clave de operación ya se usó con datos diferentes.");
        }
        if ("completado".equals(reg.estado())) {
            // Misma intención, ya resuelta antes — se devuelve el mismo resultado sin volver a
            // ejecutar nada. Esto es lo que hace que 20 reintentos produzcan un solo efecto.
            return new Resolucion.Resuelta<>(fromJson(reg.respuestaJson(), responseType));
        }

        // 'procesando' — I-03/Q-03: reclamación por compare-and-set real, no un UPDATE incondicional.
        if (!reg.leaseVencido()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya estamos procesando esta solicitud, espera un momento antes de reintentar.");
        }
        Optional<String> ownerGanado = idempotenciaService.reclamarAtomico(reg.idmId());
        if (ownerGanado.isEmpty()) {
            // Otro proceso reclamó la fila una fracción de segundo antes — no soy el dueño.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya estamos procesando esta solicitud, espera un momento antes de reintentar.");
        }
        Titular titular = new Titular(reg.idmId(), ownerGanado.get());

        // I-05: gané la reclamación, pero antes de re-ejecutar CUALQUIER lógica, intento
        // reconciliar consultando el estado real ya persistido.
        Optional<T> conciliado = reconciliar.apply(reg);
        if (conciliado.isPresent()) {
            completarSiSePuede(titular, conciliado.get());
            return new Resolucion.Resuelta<>(conciliado.get());
        }
        if (reg.pedId() != null) {
            // Ya hay efectos persistentes (pedido/pago creados) pero no se pudo reconstruir un
            // resultado definitivo — NO es seguro re-ejecutar (podría repetir un cobro cuyo
            // resultado real todavía no se conoce). Requiere conciliación manual.
            log.error("[Idempotencia] Operación {} (clave {}) reclamada con idm_ped_id={} pero sin poder " +
                    "reconciliar un resultado definitivo — se bloquea el reintento automático.",
                    operacion, clave, reg.pedId());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No pudimos confirmar el resultado de tu intento anterior. Contáctanos con el número " +
                    "de pedido más reciente antes de volver a intentarlo.");
        }
        // Sin efectos persistentes conocidos (murió antes de crear nada) — seguro re-ejecutar.
        return new Resolucion.Continuar<>(titular);
    }

    /** Q-06 (cuarta auditoría): antes, si fallaba serializar la respuesta, se guardaba "{}" y la
     *  operación quedaba marcada "completado" igual — un reintento futuro con la misma clave
     *  recibía ese "{}" y fallaba al reconstruirlo. Ahora, si la serialización falla, NO se marca
     *  completado (la fila queda 'procesando'): un reintento futuro pasará por reclamarAtomico() +
     *  reconciliar() en vez de recibir una respuesta cacheada rota. La llamada ACTUAL igual recibe
     *  el resultado correcto (ya lo tenemos en memoria) — solo no queda cacheado para replay. */
    private <T> void completarSiSePuede(Titular titular, T resultado) {
        String json;
        try {
            json = objectMapper.writeValueAsString(resultado);
        } catch (Exception e) {
            log.error("[Idempotencia] No se pudo serializar la respuesta de la operación {} para cachearla — " +
                    "la operación SÍ se completó, pero un reintento futuro deberá reconciliarse en vez de " +
                    "recibir un replay: {}", titular.idmId(), e.getMessage());
            return;
        }
        idempotenciaService.completar(titular.idmId(), titular.owner(), json);
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

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo reconstruir la respuesta cacheada de idempotencia", e);
        }
    }
}