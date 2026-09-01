package jaider.ecommerce.tienda.envio;

public record EmpaqueResponse(
        Long id,
        String nombre,
        Short largoCm,
        Short anchoCm,
        Short altoCm,
        Integer pesoGramos,
        Integer cantidadMin,
        Integer cantidadMax,
        Short orden,
        boolean activo
) {}
