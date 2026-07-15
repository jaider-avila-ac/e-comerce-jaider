package jaider.ecommerce.pedido.devolucion;

public record DireccionDevolucionRequest(
        String nombre,
        String direccion,
        String complemento,
        String departamento,
        String municipio,
        String barrio,
        String contactoNombre,
        String contactoTelefono,
        Boolean activo
) {}
