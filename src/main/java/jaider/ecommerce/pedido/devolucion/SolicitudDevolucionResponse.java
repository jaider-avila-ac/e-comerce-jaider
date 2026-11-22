package jaider.ecommerce.pedido.devolucion;

import java.time.OffsetDateTime;
import java.util.List;

public record SolicitudDevolucionResponse(
        Long id,
        Long pedidoId,
        String numeroPedido,
        String estado,
        String tipo,
        String motivo,
        DireccionDevolucionResponse direccion,
        String codigoRastreo,
        String adminNota,
        OffsetDateTime creadoEn,
        OffsetDateTime revisadoEn,
        OffsetDateTime recibidaEn,
        List<String> fotos,
        // PENDIENTE_DUO_CHIC: si el pedido se compró con un cupón, el admin tiene que verlo acá —
        // la política dice que los artículos en descuento no tienen cambio ni devolución por
        // gusto/talla/color (ver legalContent.js "cambios", sección 1). null si no se usó cupón.
        String cuponCodigo,
        Long descuento
) {}
