package jaider.ecommerce.tienda;

public record TiendaConfigResponse(
        Boolean envioGratisActivo,
        Long envioGratisDesde, // pesos COP
        Long envioCosto,       // pesos COP
        String dominioStaff,
        String emailNotificacionPedidos
) {}
