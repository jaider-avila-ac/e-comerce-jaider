package jaider.ecommerce.pedido;

public record CheckoutResponse(
        Long pedidoId,
        String numero,
        String referencia,
        String checkoutUrl,
        long montoCentavos,
        String moneda
) {
}
