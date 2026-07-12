package jaider.ecommerce.usuario.cliente;

public record ClientePerfilRequest(
        String nombre,
        String apellido,
        String telefono,
        String tipoDocumento,
        String numeroDocumento
) {
}
