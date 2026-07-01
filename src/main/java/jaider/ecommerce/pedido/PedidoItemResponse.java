package jaider.ecommerce.pedido;

import java.util.Map;

public record PedidoItemResponse(
        Long id,
        Long prdId,
        Long varId,
        String nombreSnap,
        String imagenSnap,
        Map<String, Object> variantesSnap,
        Long precioUnitario,
        Long descuentoUnitario,
        Integer cantidad,
        Long subtotal
) {}
