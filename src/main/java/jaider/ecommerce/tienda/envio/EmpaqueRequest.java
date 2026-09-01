package jaider.ecommerce.tienda.envio;

public record EmpaqueRequest(
        String nombre,
        Short largoCm,
        Short anchoCm,
        Short altoCm,
        Integer pesoGramos,
        Short orden,
        Boolean activo
) {}
