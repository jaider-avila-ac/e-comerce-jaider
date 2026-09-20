package jaider.ecommerce.tienda.envio;

/** Un renglón del carrito, reducido a lo único que hace falta para calcular el paquete. */
public record ItemParaPaquete(Long productoId, int cantidad) {}
