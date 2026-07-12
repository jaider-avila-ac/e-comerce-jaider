package jaider.ecommerce.catalogo.resena;

import java.util.List;

public record ResenaListResponse(Double ratingPromedio, Integer totalResenas, List<ResenaResponse> items) {
}
