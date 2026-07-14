package jaider.ecommerce.catalogo.resena;

import java.util.List;

public record ResenaListResponse(Double ratingPromedio, Integer totalResenas,
                                 List<Distribucion> distribucion, List<ResenaResponse> items) {
    public record Distribucion(Integer estrellas, Long cantidad, Long porcentaje) {}
}
