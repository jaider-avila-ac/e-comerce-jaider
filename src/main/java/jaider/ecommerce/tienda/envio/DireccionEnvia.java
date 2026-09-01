package jaider.ecommerce.tienda.envio;

/** Un extremo (origen o destino) listo para el payload de /ship/rate/ de Envia.com. */
public record DireccionEnvia(
        String nombre, String telefono, String calle,
        String municipio, String departamento, String codigoPostal
) {}
