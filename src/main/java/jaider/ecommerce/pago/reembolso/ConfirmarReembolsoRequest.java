package jaider.ecommerce.pago.reembolso;

/** estado: "completado" | "rechazado" | "error" — confirmación manual del admin cuando el
 *  reembolso no se pudo procesar automáticamente contra la pasarela. */
public record ConfirmarReembolsoRequest(String estado, String nota) {}
