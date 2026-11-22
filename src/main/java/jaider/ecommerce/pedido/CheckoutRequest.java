package jaider.ecommerce.pedido;

import jaider.ecommerce.usuario.cliente.ClienteDireccionRequest;

/**
 * Body del checkout hospedado (ventana de Wompi).
 * Usa direccionId para una dirección guardada, o direccionInline para una dirección puntual sin guardar.
 */
public record CheckoutRequest(
        Long direccionId,
        ClienteDireccionRequest direccionInline,
        String notas,
        // Corrección de auditoría (2026-09-01, tercera vuelta) — obligatorio para tiendas con
        // envío calculado ('envia'): el token que devolvió GET /carrito/envio-cotizacion,
        // congelando la cotización EXACTA que se le mostró al cliente. Ver CotizacionTokenService.
        String cotizacionToken,
        // PENDIENTE_DUO_CHIC — opcional: código de cupón ya validado en el carrito (ver
        // CuponService). Se vuelve a validar y canjear acá, nunca se confía en lo que diga el
        // frontend sobre si "ya se validó".
        String cuponCodigo
) {
}
