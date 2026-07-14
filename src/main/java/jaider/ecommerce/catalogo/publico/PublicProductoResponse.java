package jaider.ecommerce.catalogo.publico;

import java.util.List;
import java.util.Map;

public record PublicProductoResponse(
        Long id,
        String nombre,
        String slug,
        String descripcion,
        Long precio,          // precio base original (para tachado) — prd_precio_centavos/100
        Long precioFinal,     // precio real a cobrar (con descuento aplicado si hay oferta vigente) — el
                              // frontend nunca debe recalcularlo a partir de precio+descuento (el % está
                              // redondeado y produce un valor distinto al real)
        Integer descuento,    // % de descuento (0 si no hay) — solo para mostrar el badge, no para calcular
        String marca,
        String genero,        // lowercase: hombre, mujer, niños, unisex
        Long categoriaId,
        String categoriaNombre,
        String subcategoria,
        List<String> etiquetas,
        boolean activo,
        List<ImagenInfo> imagenes,
        List<TiendaOpcion> tallas,      // para hasStock en ProductCard
        List<TiendaVariante> variantes, // para ProductDetailPage
        List<StockVariante> stockVariantes,
        Integer stockTotal,
        Map<String, Object> caracteristicas,
        Double ratingPromedio, // null si el producto no tiene reseñas aprobadas todavía
        Integer totalResenas
) {
    public record ImagenInfo(String url, Long varId) {}
    public record TiendaOpcion(String valor, Integer stock, String hex, Long varId) {}
    public record TiendaVariante(String nombre, String tipo, boolean requerido, List<TiendaOpcion> opciones) {}
    public record StockVariante(Long id, String talla, String color, Integer stock) {}
}
