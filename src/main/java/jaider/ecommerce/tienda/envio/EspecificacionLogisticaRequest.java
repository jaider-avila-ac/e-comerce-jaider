package jaider.ecommerce.tienda.envio;

public record EspecificacionLogisticaRequest(
        String nombre,
        Integer pesoGramos,
        Short largoCm,
        Short anchoCm,
        Short altoCm,
        Boolean activo
) {}
