package jaider.ecommerce.usuario.cliente;

public record ClienteDireccionRequest(
        String direccion,
        String complemento,
        String departamento,
        String municipio,
        String barrio,
        String apartamento,
        String contactoNombre,
        String contactoTelefono
) {
}
