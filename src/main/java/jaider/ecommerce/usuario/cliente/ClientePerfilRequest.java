package jaider.ecommerce.usuario.cliente;

public record ClientePerfilRequest(
        String nombre,
        String apellido,
        String telefono,
        String tipoDocumento,
        String numeroDocumento,
        Boolean aceptaPromo // null = no tocar la preferencia actual (ver updatePerfil)
) {
}
