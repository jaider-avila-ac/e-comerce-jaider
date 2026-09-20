package jaider.ecommerce.tienda.envio;

public record TransportadoraResponse(
        Long id,
        String carrier,
        Short orden,
        boolean activo
) {}
