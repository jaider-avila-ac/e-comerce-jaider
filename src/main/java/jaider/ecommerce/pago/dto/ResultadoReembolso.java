package jaider.ecommerce.pago.dto;

/**
 * Resultado de un intento de reembolso automático contra la pasarela. Si {@code exitoso} es
 * false, el reembolso queda pendiente de gestión manual — nunca se marca como completado sin
 * una confirmación real de la pasarela.
 */
public record ResultadoReembolso(
        boolean exitoso,
        String gatewayRefundId,
        String respuestaJson,
        String mensaje
) {}
