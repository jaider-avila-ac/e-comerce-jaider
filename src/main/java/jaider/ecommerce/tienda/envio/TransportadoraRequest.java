package jaider.ecommerce.tienda.envio;

public record TransportadoraRequest(
        String carrier,
        Short orden,
        Boolean activo
) {}
