package jaider.ecommerce.tienda.banner;

public record BannerRequest(
        String posicion,
        String tipo,
        String url,
        String titulo,
        String ctaLink,
        Short orden,
        Boolean activo
) {}
