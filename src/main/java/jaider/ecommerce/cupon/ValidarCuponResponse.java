package jaider.ecommerce.cupon;

/** Respuesta de la validación "en vivo" que hace el carrito ANTES de pagar — nunca consume un
 *  uso (eso solo pasa en el checkout real, ver CuponService.canjear). Si no es válido, motivo
 *  explica por qué (código inexistente, inactivo, o sin usos disponibles) y el resto va en null. */
public record ValidarCuponResponse(
        boolean valido,
        String motivo,
        String codigo,
        String tipoDescuento,
        Long valor,
        Long descuentoCentavos
) {}
