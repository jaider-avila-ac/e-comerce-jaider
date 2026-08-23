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
        Boolean envioContraEntrega,
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
        Long colaboradorId,       // null hasta que alguien se autoasigne el pedido
        String colaboradorNombre,
        Long sucursalId,          // tienda física que gestionó la venta — ver Pedido.sucursalId
        String sucursalNombre,
        String canal,             // "online" | "local" — ver Pedido.canal
        List<PedidoItemResponse> items   // null en el listado, poblado en el detalle
) {}
