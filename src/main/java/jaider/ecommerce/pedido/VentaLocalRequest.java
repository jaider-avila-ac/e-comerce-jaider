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
    public record ItemVentaLocal(Long prdId, Long varId, Integer cantidad) {}
}
