package jaider.ecommerce.pedido.devolucion;

public record DireccionDevolucionResponse(
        Long id,
        String nombre,
        String direccion,
        String complemento,
        String departamento,
        String municipio,
        String barrio,
        String contactoNombre,
        String contactoTelefono,
        boolean activo
) {}
