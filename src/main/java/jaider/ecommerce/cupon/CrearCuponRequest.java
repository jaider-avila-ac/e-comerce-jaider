package jaider.ecommerce.cupon;

/** codigo: ignorado si generarAleatorio=true (el backend genera uno); obligatorio si es false
 *  (código personalizado). maxUsos: null o vacío = 1 (un solo uso). */
public record CrearCuponRequest(
        String codigo,
        boolean generarAleatorio,
        String tipoDescuento,
        Long valor,
        Integer maxUsos
) {}
