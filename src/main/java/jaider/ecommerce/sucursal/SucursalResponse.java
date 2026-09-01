package jaider.ecommerce.sucursal;

public record SucursalResponse(
        Long id,
        String nombre,
        String whatsapp,
        // Dirección de origen para envíos (PLAN_INTEGRACION_ENVIA.md, Fase 3) — disponible desde
        // ya aunque todavía no hay un endpoint que la escriba.
        String envioOrigenNombre,
        String envioOrigenTelefono,
        String envioOrigenDireccion,
        String envioOrigenComplemento,
        String envioOrigenDepartamento,
        String envioOrigenMunicipio,
        String envioOrigenCodigoPostal
) {
}
