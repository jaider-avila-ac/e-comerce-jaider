package jaider.ecommerce.pedido;

import jaider.ecommerce.pago.reembolso.ReembolsoResponse;

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
        String linkSeguimiento,
        String transportadora,
        String codigoRastreo,
        String mostrarSeguimiento,
        OffsetDateTime confirmadoClienteEn,
        String metodoPago,
        String cancelMotivo,
        String cancelMotivoOtro,
        String cancelNota,
        OffsetDateTime canceladoEn,
        ReembolsoResponse reembolso,
        List<PedidoItemResponse> items   // null en el listado, poblado en el detalle
) {}
