package jaider.ecommerce.pedido;

import jaider.ecommerce.pago.dto.CobroTarjetaResultado;
import jaider.ecommerce.pago.dto.WebhookTransactionEvent;
import jaider.ecommerce.pago.service.PagoConfirmacionService;
import jaider.ecommerce.pago.service.PaymentGateway;
import jaider.ecommerce.pedido.PedidoCreacionService.PagoInfo;
import jaider.ecommerce.pedido.PedidoCreacionService.PedidoCreado;
import jaider.ecommerce.shared.idempotencia.IdempotenciaGuard;
import jaider.ecommerce.shared.idempotencia.IdempotenciaService;
import jaider.ecommerce.shared.idempotencia.IdempotenciaService.Registro;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Orquesta los dos flujos de pago de un checkout, ambos sobre el mismo carrito/pedido:
 *   - iniciarCheckoutHospedado: crea el pedido y devuelve la URL de la ventana de Wompi;
 *     la confirmación llega después, de forma asíncrona, por PagoWebhookService.
 *   - pagarConTarjeta: cobra de inmediato con una tarjeta tokenizada, sin ventana de Wompi.
 *
 * Ambos métodos están envueltos en IdempotenciaGuard.ejecutar() (ver REAUDITORIA_FUNCIONAL_E_
 * IDEMPOTENCIA.md y TERCERA_AUDITORIA_FUNCIONAL_E_IDEMPOTENCIA.md, P0): el cliente manda un
 * header Idempotency-Key por cada intento de compra; mientras no reciba un resultado definitivo,
 * reenvía la MISMA clave en cualquier reintento (doble clic, timeout, reenvío de proxy, recarga).
 * El guard garantiza que la lógica de abajo corre como máximo una vez por clave — solicitudes
 * repetidas reciben la misma respuesta ya calculada o, si la operación quedó a medias, una
 * reconstruida consultando lo que realmente pasó (nunca repitiendo un cobro a ciegas).
 *
 * pagarConTarjeta() es deliberadamente NO transaccional a nivel de método: crea el pedido y el
 * pago (que sí se confirman en su propia transacción antes de continuar) y solo después llama a
 * Wompi por HTTP. Así el cobro real nunca ocurre dentro de una transacción de BD abierta, y si la
 * confirmación posterior falla, el pago ya quedó registrado como aprobado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PedidoCheckoutService {

    private final PedidoCreacionService pedidoCreacionService;
    private final PaymentGateway paymentGateway;
    private final PagoConfirmacionService confirmacionService;
    private final IdempotenciaGuard idempotenciaGuard;
    private final IdempotenciaService idempotenciaService;

    @Value("${frontend.tienda-url}")
    private String frontendTiendaUrl;

    @Transactional
    public CheckoutResponse iniciarCheckoutHospedado(Long usrId, Long tndId, CheckoutRequest req, String idempotencyKey) {
        // Q-04 (cuarta auditoría): el DTO solo, sin el contenido real del carrito, no identifica
        // "qué se está comprando" — dos carritos distintos con la misma dirección/notas se
        // hubieran tratado como la misma intención. firmarCarrito() lee el carrito ACTUAL del
        // usuario (server-side, no lo que mande el cliente) y lo mete en el hash.
        Map<String, Object> hashBasis = new LinkedHashMap<>();
        hashBasis.put("direccionId", req.direccionId());
        hashBasis.put("direccionInline", req.direccionInline());
        hashBasis.put("notas", req.notas());
        hashBasis.put("carrito", pedidoCreacionService.firmarCarrito(usrId));

        // Todo el flujo hospedado es una sola transacción (@Transactional a nivel de método): o
        // persiste completo (pedido + pago + idempotencia "completado") o no persiste nada de eso
        // — por eso reconciliar() acá solo necesita reconstruir desde lo ya guardado, nunca hace
        // falta consultar a Wompi (el cobro real ocurre después, en su ventana, no acá).
        return idempotenciaGuard.ejecutar(tndId, usrId, "checkout_hospedado", idempotencyKey, hashBasis,
                CheckoutResponse.class,
                this::reconciliarHospedado,
                idmId -> {
                    PedidoCreado pedido = pedidoCreacionService.crearDesdeCarrito(
                            usrId, tndId, req.direccionId(), req.direccionInline(), req.notas());

                    String referencia = paymentGateway.generarReferencia(tndId, pedido.pedId());
                    pedidoCreacionService.crearPago(pedido.pedId(), usrId, referencia, pedido.totalCentavos(), null);
                    // Igual que en tarjeta: asociar el pedido a la clave apenas existe, para que
                    // reconciliarHospedado() lo encuentre si el proceso muere antes de completar().
                    idempotenciaService.registrarPedido(idmId, pedido.pedId());

                    String redirectUrl = frontendTiendaUrl + "/pedido/resultado?numero=" + pedido.numero();
                    String checkoutUrl = paymentGateway.buildCheckoutUrl(referencia, pedido.totalCentavos(), "COP", redirectUrl);

                    return new CheckoutResponse(pedido.pedId(), pedido.numero(), referencia, checkoutUrl,
                            pedido.totalCentavos(), "COP");
                });
    }

    public PagoTarjetaResponse pagarConTarjeta(Long usrId, Long tndId, CheckoutTarjetaRequest req, String idempotencyKey) {
        // hashBasis excluye cardToken/acceptanceToken/personalAuthToken a propósito (ver I-06):
        // esos campos cambian cada vez que el navegador retokeniza la MISMA tarjeta (ej. tras una
        // recarga), pero identifican la misma intención de compra — solo lo que sí define "qué
        // se está comprando y a dónde" debe hacer que dos solicitudes cuenten como intenciones
        // distintas.
        Map<String, Object> hashBasis = new LinkedHashMap<>();
        hashBasis.put("direccionId", req.direccionId());
        hashBasis.put("direccionInline", req.direccionInline());
        hashBasis.put("notas", req.notas());
        hashBasis.put("carrito", pedidoCreacionService.firmarCarrito(usrId)); // Q-04

        return idempotenciaGuard.ejecutar(tndId, usrId, "checkout_tarjeta", idempotencyKey, hashBasis,
                PagoTarjetaResponse.class,
                this::reconciliarTarjeta,
                idmId -> pagarConTarjetaReal(usrId, tndId, req, idmId));
    }

    private PagoTarjetaResponse pagarConTarjetaReal(Long usrId, Long tndId, CheckoutTarjetaRequest req, Long idmId) {
        PedidoCreado pedido = pedidoCreacionService.crearDesdeCarrito(
                usrId, tndId, req.direccionId(), req.direccionInline(), req.notas());

        String referencia = paymentGateway.generarReferencia(tndId, pedido.pedId());
        Long pagoId = pedidoCreacionService.crearPago(pedido.pedId(), usrId, referencia, pedido.totalCentavos(), "CARD");
        String email = pedidoCreacionService.obtenerEmail(usrId);

        // I-04/I-05: apenas existe un pedido/pago real, se lo asocia a la clave de idempotencia —
        // ANTES de intentar cualquier cobro. Así, si el proceso muere en cualquier punto de acá en
        // adelante, una reclamación futura sabe (por idm_ped_id) que ya hay efectos persistentes y
        // los reconcilia en vez de crear un pedido nuevo o recobrar a ciegas.
        idempotenciaService.registrarPedido(idmId, pedido.pedId());

        Long fuentePagoId;
        try {
            fuentePagoId = paymentGateway.crearFuentePago(
                    req.cardToken(), email, req.acceptanceToken(), req.personalAuthToken());
        } catch (Exception e) {
            // Fallo acá es de validación/tokenización — NUNCA llega a cobrar. Es seguro liberar la
            // clave para un reintento inmediato, pero primero hay que cancelar el pedido/pago
            // huérfanos que sí se alcanzaron a crear (I-04) para que no queden "pendiente_pago"
            // colgados si el cliente nunca vuelve a intentar.
            log.error("[Checkout Tarjeta] Error creando fuente de pago para pedido {}: {}", pedido.pedId(), e.getMessage());
            confirmacionService.cancelarPedidoNoAprobado(pagoId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo validar la tarjeta. Verifica los datos e intenta de nuevo.");
        }

        CobroTarjetaResultado resultado;
        try {
            resultado = paymentGateway.cobrarFuentePago(fuentePagoId, email, pedido.totalCentavos(), referencia);
        } catch (Exception e) {
            // A diferencia del catch de arriba, ESTE fallo es ambiguo: no sabemos si Wompi sí
            // procesó el cobro antes de que la llamada fallara (timeout de red, etc.). Por eso NO
            // se relanza como excepción (eso liberaría la clave de idempotencia y dejaría que un
            // reintento vuelva a llamar a Wompi, arriesgando un doble cobro real) — se devuelve
            // como respuesta normal, que el guard cachea igual que cualquier resultado definitivo.
            // Si el cliente reintenta con la MISMA clave antes de que esto se cachee del todo,
            // reconciliarTarjeta() consultará a Wompi directamente por la referencia.
            log.error("[Checkout Tarjeta] Error ambiguo cobrando pedido {} (pago {}) — requiere revisión manual, NO se reintenta automáticamente: {}",
                    pedido.pedId(), pagoId, e.getMessage());
            return new PagoTarjetaResponse(null, "ERROR",
                    "No pudimos confirmar el resultado de tu pago. No vuelvas a intentar con la misma tarjeta — contáctanos con tu número de pedido " + pedido.numero() + " antes de hacer un nuevo intento.",
                    pedido.pedId(), pedido.numero());
        }

        Map<String, Object> resumen = new LinkedHashMap<>();
        resumen.put("gatewayTxId", resultado.gatewayTxId());
        resumen.put("statusMessage", resultado.statusMessage());

        if (resultado.aprobado()) {
            confirmacionService.registrarAprobado(pagoId, resultado.gatewayTxId(), "CARD", resumen);
            try {
                confirmacionService.confirmarPedido(pagoId);
            } catch (Exception e) {
                log.error("[Checkout Tarjeta] confirmarPedido falló tras cobro aprobado — pago {} requiere revisión manual: {}",
                        pagoId, e.getMessage());
            }
            return new PagoTarjetaResponse(resultado.gatewayTxId(), "APPROVED",
                    "Pago aprobado. Recibimos tu compra y pronto sera revisada.", pedido.pedId(), pedido.numero());
        }

        if (resultado.pendiente()) {
            // El pago queda PENDING; PagoWebhookService lo resolverá cuando llegue la confirmación de Wompi.
            return new PagoTarjetaResponse(resultado.gatewayTxId(), "PENDING",
                    "Tu pago esta siendo procesado. La compra aparecera cuando Wompi la confirme.", pedido.pedId(), pedido.numero());
        }

        String estado = "ERROR".equals(resultado.status()) ? "ERROR" : "DECLINED";
        confirmacionService.registrarRechazado(pagoId, estado, resultado.statusMessage(),
                resultado.gatewayTxId(), "CARD", resumen);
        confirmacionService.cancelarPedidoNoAprobado(pagoId);
        String motivo = resultado.statusMessage() != null ? resultado.statusMessage() : "verifica los datos de tu tarjeta";
        return new PagoTarjetaResponse(resultado.gatewayTxId(), "DECLINED",
                "Pago rechazado: " + motivo + ".", pedido.pedId(), pedido.numero());
    }

    // ── Reconciliación (I-05): se llama SIEMPRE antes de reejecutar una operación reclamada ──

    /** Hospedado es enteramente transaccional (@Transactional en el método) — si el proceso murió
     *  a medias, o todo persistió (incluido "completado") o nada persistió excepto la propia fila
     *  de idempotencia. Reconstruir es solo releer lo que ya está, nunca hace falta tocar Wompi
     *  (el cobro real ocurre después, en la ventana hospedada, no en este método). */
    private Optional<CheckoutResponse> reconciliarHospedado(Registro reg) {
        if (reg.pedId() == null) return Optional.empty();
        return pedidoCreacionService.obtenerUltimoPago(reg.pedId()).map(pago -> {
            String redirectUrl = frontendTiendaUrl + "/pedido/resultado?numero=" + pago.numeroPedido();
            // buildCheckoutUrl es una construcción local de URL (no llama a Wompi ni cobra nada),
            // así que reconstruirla es seguro y determinístico dado el mismo referencia/monto.
            String checkoutUrl = paymentGateway.buildCheckoutUrl(pago.referencia(), pago.montoCentavos(), "COP", redirectUrl);
            return new CheckoutResponse(reg.pedId(), pago.numeroPedido(), pago.referencia(), checkoutUrl,
                    pago.montoCentavos(), "COP");
        });
    }

    /** Tarjeta NO es transaccional a nivel de método — el pedido/pago pueden persistir aunque el
     *  proceso muera antes de terminar. Antes de reejecutar (lo que podría cobrar dos veces), se
     *  intenta reconstruir el resultado real: primero desde lo que ya quedó en nuestra BD, y si
     *  seguía PENDING (no se sabe si Wompi cobró), se consulta directamente a Wompi por
     *  referencia — igual que el flujo de reconciliación manual que ya existe para webhooks
     *  perdidos (ver PaymentGateway.consultarTransaccion). */
    private Optional<PagoTarjetaResponse> reconciliarTarjeta(Registro reg) {
        if (reg.pedId() == null) return Optional.empty(); // nunca se creó nada — seguro reintentar desde cero

        Optional<PagoInfo> pagoOpt = pedidoCreacionService.obtenerUltimoPago(reg.pedId());
        if (pagoOpt.isEmpty()) return Optional.empty();
        PagoInfo pago = pagoOpt.get();

        if ("APPROVED".equals(pago.estado())) {
            return Optional.of(new PagoTarjetaResponse(pago.gatewayTxId(), "APPROVED",
                    "Pago aprobado. Recibimos tu compra y pronto sera revisada.", reg.pedId(), pago.numeroPedido()));
        }
        if ("DECLINED".equals(pago.estado()) || "ERROR".equals(pago.estado())) {
            String msg = pago.motivoRechazo() != null ? pago.motivoRechazo() : "verifica los datos de tu tarjeta";
            return Optional.of(new PagoTarjetaResponse(pago.gatewayTxId(), "DECLINED",
                    "Pago rechazado: " + msg + ".", reg.pedId(), pago.numeroPedido()));
        }

        // Nuestra BD sigue en PENDING — el proceso pudo haber muerto justo después de que Wompi
        // cobrara de verdad, sin que llegáramos a registrarlo. Se pregunta directo a la fuente.
        Optional<WebhookTransactionEvent> txOpt = paymentGateway.consultarTransaccion(pago.referencia());
        if (txOpt.isEmpty()) {
            log.warn("[Idempotencia][Reconciliación] No se pudo consultar Wompi para referencia {} (pedido {}) — " +
                    "resultado desconocido, se bloquea el reintento automático.", pago.referencia(), reg.pedId());
            return Optional.empty();
        }
        WebhookTransactionEvent tx = txOpt.get();
        if ("APPROVED".equals(tx.status())) {
            confirmacionService.registrarAprobado(pago.pagoId(), tx.gatewayTxId(), tx.metodoPago(), Map.of());
            try {
                confirmacionService.confirmarPedido(pago.pagoId());
            } catch (Exception e) {
                log.error("[Idempotencia][Reconciliación] confirmarPedido falló tras reconciliar cobro aprobado — pago {}: {}",
                        pago.pagoId(), e.getMessage());
            }
            return Optional.of(new PagoTarjetaResponse(tx.gatewayTxId(), "APPROVED",
                    "Pago aprobado. Recibimos tu compra y pronto sera revisada.", reg.pedId(), pago.numeroPedido()));
        }
        if ("DECLINED".equals(tx.status()) || "ERROR".equals(tx.status()) || "VOIDED".equals(tx.status())) {
            String estado = "DECLINED".equals(tx.status()) ? "DECLINED" : "ERROR";
            confirmacionService.registrarRechazado(pago.pagoId(), estado, null, tx.gatewayTxId(), tx.metodoPago(), Map.of());
            confirmacionService.cancelarPedidoNoAprobado(pago.pagoId());
            return Optional.of(new PagoTarjetaResponse(tx.gatewayTxId(), "DECLINED",
                    "Pago rechazado.", reg.pedId(), pago.numeroPedido()));
        }
        // Wompi también responde algo no definitivo (PENDING u otro) — genuinamente sin resolver.
        return Optional.empty();
    }
}