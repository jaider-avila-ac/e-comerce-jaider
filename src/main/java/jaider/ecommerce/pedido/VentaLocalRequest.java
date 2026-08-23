package jaider.ecommerce.pedido;

import java.util.List;

/**
 * Venta registrada en persona por un admin/colaborador (no viene de la tienda online).
 * Si {@code usrId} viene, se usa ese cliente ya existente en la tienda; si no, se resuelve
 * o crea uno nuevo con {@code nombre}+{@code numeroDocumento} (único por tienda).
 */
public record VentaLocalRequest(
        Long usrId,
        String nombre,
        String tipoDocumento,
        String numeroDocumento,
        List<ItemVentaLocal> items,
        String metodoPago,
        String notas
) {
    /** precioVenta (pesos, opcional): precio realmente cobrado por el vendedor en mostrador, si
     *  es distinto del precio de catálogo — ej. catálogo $75.000, se lo vende en $70.000 por
     *  regateo. null = se cobra el precio de catálogo (con oferta vigente si aplica) tal cual.
     *  Debe ser mayor a $0 y nunca mayor al de catálogo (esto es un descuento, no un recargo). */
    public record ItemVentaLocal(Long prdId, Long varId, Integer cantidad, Long precioVenta) {}
}
