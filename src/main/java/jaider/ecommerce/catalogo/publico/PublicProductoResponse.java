package jaider.ecommerce.catalogo.publico;

import java.util.List;
import java.util.Map;

public record PublicProductoResponse(
        Long id,
        String nombre,
        String slug,
        String descripcion,
        Long precio,          // precio base original (para tachado) — prd_precio_centavos/100
        Integer descuento,    // % de descuento (0 si no hay)
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
        Map<String, Object> caracteristicas
) {
    public record ImagenInfo(String url, Long varId) {}
    public record TiendaOpcion(String valor, Integer stock, String hex, Long varId) {}
    public record TiendaVariante(String nombre, String tipo, boolean requerido, List<TiendaOpcion> opciones) {}
    public record StockVariante(Long id, String talla, String color, Integer stock) {}
}
