package jaider.ecommerce.tienda.envio;

/** Lo que se le manda a Envia para cotizar (Fase 3) — nunca la suma de las dimensiones de cada
 *  producto, siempre las del empaque elegido. */
public record PaqueteCalculado(
        int pesoTotalGramos,
        Long empaqueId,
        String empaqueNombre,
        short largoCm,
        short anchoCm,
        short altoCm
) {}
