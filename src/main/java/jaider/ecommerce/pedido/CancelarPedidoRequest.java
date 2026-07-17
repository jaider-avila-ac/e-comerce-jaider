package jaider.ecommerce.pedido;

/** motivo: uno de los valores fijos del catálogo (ver PedidoService.MOTIVOS_CANCELACION).
 *  motivoOtro: obligatorio solo cuando motivo = "otro". nota: opcional, para cualquier motivo. */
public record CancelarPedidoRequest(String motivo, String motivoOtro, String nota) {}
