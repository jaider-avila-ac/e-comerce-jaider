package jaider.ecommerce.tienda;

public record TiendaConfigResponse(
        String envioModo,       // "contra_entrega" | "fijo" | "envia"
        Boolean envioGratisActivo,
        Long envioGratisDesde, // pesos COP
        Long envioCosto,       // pesos COP
        String dominioStaff,
        String emailNotificacionPedidos,
        String razonSocial,
        String nit,
        String emailContacto,
        String colorPrincipal,
        String enviaAmbiente    // "sandbox" | "produccion" — solo importa si envioModo="envia"
) {}
