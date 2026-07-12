package jaider.ecommerce.notificacion.dto;

import java.time.OffsetDateTime;

public record NotificacionClienteResponse(
        Long id,
        String tipo,
        String titulo,
        String mensaje,
        String entidadTipo,
        Long entidadId,
        String imagenUrl,
        boolean leida,
        OffsetDateTime creadoEn
) {}
