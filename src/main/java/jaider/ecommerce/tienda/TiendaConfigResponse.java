package jaider.ecommerce.tienda;

public record TiendaConfigResponse(
        String envioModo,       // "contra_entrega" | "fijo"
        Boolean envioGratisActivo,
        Long envioGratisDesde, // pesos COP
        Long envioCosto,       // pesos COP
        String dominioStaff,
        String emailNotificacionPedidos
) {}
