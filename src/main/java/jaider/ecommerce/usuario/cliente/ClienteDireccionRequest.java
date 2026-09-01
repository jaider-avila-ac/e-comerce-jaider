package jaider.ecommerce.usuario.cliente;

public record ClienteDireccionRequest(
        String direccion,
        String complemento,
        String departamento,
        String municipio,
        String barrio,
        String apartamento,
        String contactoNombre,
        String contactoTelefono,
        // PLAN_INTEGRACION_ENVIA.md, Fase 3 — Envia lo exige sí o sí para cotizar en Colombia
        // (confirmado con una llamada real a su API, no es una suposición).
        String codigoPostal
) {
}
