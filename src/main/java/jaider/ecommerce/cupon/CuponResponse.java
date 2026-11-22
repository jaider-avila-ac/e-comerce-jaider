package jaider.ecommerce.cupon;

import java.time.OffsetDateTime;

public record CuponResponse(
        Long id,
        String codigo,
        String tipoDescuento,
        Long valor,
        int maxUsos,
        int usosActuales,
        boolean activo,
        OffsetDateTime creadoEn
) {
    public static CuponResponse de(Cupon c) {
        return new CuponResponse(c.getId(), c.getCodigo(), c.getTipoDescuento(), c.getValor(),
                c.getMaxUsos(), c.getUsosActuales(), c.isActivo(), c.getCreadoEn());
    }
}
