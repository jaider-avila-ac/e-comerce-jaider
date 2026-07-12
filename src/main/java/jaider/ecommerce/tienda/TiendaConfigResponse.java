package jaider.ecommerce.tienda;

public record TiendaConfigResponse(
        Boolean envioGratisActivo,
        Long envioGratisDesde, // pesos COP
        String dominioStaff
) {}
