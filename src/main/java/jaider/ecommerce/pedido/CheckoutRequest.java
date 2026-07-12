package jaider.ecommerce.pedido;

import jaider.ecommerce.usuario.cliente.ClienteDireccionRequest;

/**
 * Body del checkout hospedado (ventana de Wompi).
 * Usa direccionId para una dirección guardada, o direccionInline para una dirección puntual sin guardar.
 */
public record CheckoutRequest(
        Long direccionId,
        ClienteDireccionRequest direccionInline,
        String notas
) {
}
