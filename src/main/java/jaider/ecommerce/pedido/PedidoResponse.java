package jaider.ecommerce.pedido;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record PedidoResponse(
        Long id,
        String numero,
        String estado,
        String clienteNombre,
        String clienteEmail,
        Map<String, Object> dirSnapshot,
        Long subtotal,
        Long descuento,
        Long envio,
        Long total,
        String notas,
        OffsetDateTime creadoEn,
        Boolean alertaStock,
        List<PedidoItemResponse> items   // null en el listado, poblado en el detalle
) {}
