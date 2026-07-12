package jaider.ecommerce.notificacion.dto;

import java.time.OffsetDateTime;

public record NotificacionAdminResponse(
        Long id,
        String tipo,
        String titulo,
        String mensaje,
        String entidadTipo,
        Long entidadId,
        boolean leida,
        OffsetDateTime creadoEn
) {}
