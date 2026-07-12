package jaider.ecommerce.pedido;

import jaider.ecommerce.pago.dto.CobroTarjetaResultado;
import jaider.ecommerce.pago.service.PagoConfirmacionService;
import jaider.ecommerce.pago.service.PaymentGateway;
import jaider.ecommerce.pedido.PedidoCreacionService.PedidoCreado;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orquesta los dos flujos de pago de un checkout, ambos sobre el mismo carrito/pedido:
 *   - iniciarCheckoutHospedado: crea el pedido y devuelve la URL de la ventana de Wompi;
 *     la confirmación llega después, de forma asíncrona, por PagoWebhookService.
 *   - pagarConTarjeta: cobra de inmediato con una tarjeta tokenizada, sin ventana de Wompi.
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

    @Value("${frontend.tienda-url}")
    private String frontendTiendaUrl;

    @Transactional
    public CheckoutResponse iniciarCheckoutHospedado(Long usrId, Long tndId, CheckoutRequest req) {
        PedidoCreado pedido = pedidoCreacionService.crearDesdeCarrito(
                usrId, tndId, req.direccionId(), req.direccionInline(), req.notas());

        String referencia = paymentGateway.generarReferencia(tndId, pedido.pedId());
        pedidoCreacionService.crearPago(pedido.pedId(), usrId, referencia, pedido.totalCentavos(), null);

        String redirectUrl = frontendTiendaUrl + "/pedido/resultado?numero=" + pedido.numero();
        String checkoutUrl = paymentGateway.buildCheckoutUrl(referencia, pedido.totalCentavos(), "COP", redirectUrl);

        return new CheckoutResponse(pedido.pedId(), pedido.numero(), referencia, checkoutUrl,
                pedido.totalCentavos(), "COP");
    }

    public PagoTarjetaResponse pagarConTarjeta(Long usrId, Long tndId, CheckoutTarjetaRequest req) {
        PedidoCreado pedido = pedidoCreacionService.crearDesdeCarrito(
                usrId, tndId, req.direccionId(), req.direccionInline(), req.notas());

        String referencia = paymentGateway.generarReferencia(tndId, pedido.pedId());
        Long pagoId = pedidoCreacionService.crearPago(pedido.pedId(), usrId, referencia, pedido.totalCentavos(), "CARD");
        String email = pedidoCreacionService.obtenerEmail(usrId);

        Long fuentePagoId;
        try {
            fuentePagoId = paymentGateway.crearFuentePago(
                    req.cardToken(), email, req.acceptanceToken(), req.personalAuthToken());
        } catch (Exception e) {
            log.error("[Checkout Tarjeta] Error creando fuente de pago para pedido {}: {}", pedido.pedId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo validar la tarjeta. Verifica los datos e intenta de nuevo.");
        }

        CobroTarjetaResultado resultado;
        try {
            resultado = paymentGateway.cobrarFuentePago(fuentePagoId, email, pedido.totalCentavos(), referencia);
        } catch (Exception e) {
            log.error("[Checkout Tarjeta] Error cobrando pedido {}: {}", pedido.pedId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al procesar el pago. Por favor intenta de nuevo.");
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
}
