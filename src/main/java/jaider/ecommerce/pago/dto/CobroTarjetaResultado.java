package jaider.ecommerce.pago.dto;

/**
 * Resultado síncrono de un cobro directo con tarjeta vía POST /v1/transactions.
 * El estado final definitivo también puede llegar después por webhook (el flujo debe ser idempotente).
 */
public record CobroTarjetaResultado(
        String gatewayTxId,
        String status,
        String statusMessage,
        boolean fuentePagoInvalida
) {
    public boolean aprobado()  { return "APPROVED".equals(status); }
    public boolean rechazado() { return "DECLINED".equals(status) || "ERROR".equals(status); }
    public boolean pendiente() { return "PENDING".equals(status); }
}
