package jaider.ecommerce.auditoria;

import java.util.Map;

public record AuditoriaResponse(
        Long id,
        String adminNombre,
        String adminEmail,
        String accion,
        String entidad,
        Long entidadId,
        Map<String, Object> detalle,
        String creadoEn // "dd/MM/yyyy HH:mm" hora Bogotá — mismo formato que el resto del admin
) {
}
