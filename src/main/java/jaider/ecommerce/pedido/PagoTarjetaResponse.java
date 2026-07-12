package jaider.ecommerce.pedido;

/** status: APPROVED | PENDING | DECLINED */
public record PagoTarjetaResponse(
        String gatewayTxId,
        String status,
        String mensaje,
        Long pedidoId,
        String numero
) {
}
