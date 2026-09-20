package jaider.ecommerce.tienda.envio;

/**
 * Un renglón listo para el arreglo {@code packages[]} de la API real de Envia.com
 * (docs.envia.com/docs/shipping-multiple-packages) — un renglón por cada empaque DISTINTO
 * usado en el carrito, con la cantidad total de artículos que comparten ese mismo empaque.
 * Envia mismo suma peso/dimensiones de todos los renglones al cotizar — este servicio NO
 * combina nada, solo agrupa.
 */
public record PaqueteCalculado(
        Long empaqueId,
        String empaqueNombre,
        int cantidad,            // "amount" en la API de Envia
        int pesoGramosPorUnidad, // peso de UN empaque — Envia lo multiplica por "cantidad"
        short largoCm,
        short anchoCm,
        short altoCm
) {}
