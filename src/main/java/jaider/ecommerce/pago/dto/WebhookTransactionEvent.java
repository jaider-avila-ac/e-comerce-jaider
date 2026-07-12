package jaider.ecommerce.pago.dto;

/**
 * Evento de transacción normalizado, agnóstico de pasarela.
 * Cada implementación de PaymentGateway traduce su formato nativo a este objeto.
 *
 * status normalizado: PENDING | APPROVED | DECLINED | VOIDED | ERROR (mismos valores que estado_pago).
 */
public record WebhookTransactionEvent(
        String eventType,
        String status,
        String referencia,
        String gatewayTxId,
        String metodoPago,
        Long amountCentavos,
        String currency
) {
}
