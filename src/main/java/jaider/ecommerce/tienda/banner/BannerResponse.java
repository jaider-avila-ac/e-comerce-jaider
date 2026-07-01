package jaider.ecommerce.tienda.banner;

import java.time.OffsetDateTime;

public record BannerResponse(
        Long id,
        String posicion,
        String tipo,
        String url,
        String titulo,
        String ctaLink,
        Short orden,
        Boolean activo,
        OffsetDateTime creadoEn
) {}
