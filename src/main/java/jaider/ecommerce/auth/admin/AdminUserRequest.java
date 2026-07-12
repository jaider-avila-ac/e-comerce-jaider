package jaider.ecommerce.auth.admin;

/**
 * usuario = solo la parte antes del "@"; el email final se arma con el dominio configurado
 * por la tienda. Los campos de perfil (apellido en adelante) son opcionales.
 */
public record AdminUserRequest(
        String usuario, String password, String nombre, String rol,
        String apellido, String telefono, String cargo,
        String tipoDocumento, String numeroDocumento, String fechaNacimiento
) {
}
