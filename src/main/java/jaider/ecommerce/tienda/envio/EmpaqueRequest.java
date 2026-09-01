package jaider.ecommerce.tienda.envio;

public record EmpaqueRequest(
        String nombre,
        Short largoCm,
        Short anchoCm,
        Short altoCm,
        Integer pesoGramos,
        Integer cantidadMin,   // opcional en update() — null = no lo toques; en create() default 1
        Integer cantidadMax,   // opcional — null = sin límite superior
        Short orden,
        Boolean activo
) {}
