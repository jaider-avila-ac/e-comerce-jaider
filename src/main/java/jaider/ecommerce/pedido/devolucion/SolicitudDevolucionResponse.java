package jaider.ecommerce.pedido.devolucion;

import java.time.OffsetDateTime;
import java.util.List;

public record SolicitudDevolucionResponse(
        Long id,
        Long pedidoId,
        String numeroPedido,
        String estado,
        String motivo,
        DireccionDevolucionResponse direccion,
        String codigoRastreo,
        String adminNota,
        OffsetDateTime creadoEn,
        OffsetDateTime revisadoEn,
        OffsetDateTime recibidaEn,
        List<String> fotos
) {}
