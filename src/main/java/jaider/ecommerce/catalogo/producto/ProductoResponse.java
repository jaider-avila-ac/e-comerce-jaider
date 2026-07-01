package jaider.ecommerce.catalogo.producto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ProductoResponse(
        Long id,
        Long catId,
        Long subId,
        String nombre,
        String slug,
        String descripcion,
        Long precio,              // COP pesos — precio que paga el cliente ahora
        Long precioAntes,         // COP pesos — precio tachado (solo si hay oferta activa)
        OffsetDateTime ofertaHasta, // vencimiento de oferta (null = sin límite o sin oferta)
        Map<String, Object> fichaTecnica,
        Boolean activo,
        OffsetDateTime creadoEn,
        Integer stockTotal,   // suma de stock de variantes activas
        List<VarianteResponse> variantes,
        List<ImagenResponse> imagenes
) {}
