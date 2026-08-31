package jaider.ecommerce.tienda.superadmin;

import java.time.OffsetDateTime;

/**
 * Estado de UN campo de credencial, SIN el valor — "configurada": true/false, de dónde viene
 * (BD cifrada o variable de entorno) y quién/cuándo lo guardó por última vez desde este panel
 * (null si viene de una variable de entorno, porque esas no se tocan desde acá).
 */
public record CampoEstadoResponse(
        String campo,
        boolean configurada,
        String origen,          // "BD" | "ENV" | null si no está configurada en absoluto
        OffsetDateTime actualizadoEn,
        String actualizadoPorEmail
) {}
