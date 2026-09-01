package jaider.ecommerce.tienda.envio;

public record EspecificacionLogisticaResponse(
        Long id,
        String nombre,
        Integer pesoGramos,
        Short largoCm,
        Short anchoCm,
        Short altoCm,
        boolean activo
) {}
