package jaider.ecommerce.pedido;

import jaider.ecommerce.usuario.cliente.ClienteDireccionRequest;

/** Body del checkout con tarjeta tokenizada (sin ventana de Wompi, cobro único). */
public record CheckoutTarjetaRequest(
        Long direccionId,
        ClienteDireccionRequest direccionInline,
        String notas,
        String cardToken,
        String acceptanceToken,
        String personalAuthToken,
        // Corrección de auditoría (2026-09-01, tercera vuelta) — ver CheckoutRequest.cotizacionToken.
        String cotizacionToken
) {
}
