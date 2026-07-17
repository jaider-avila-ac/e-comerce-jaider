package jaider.ecommerce.pago.reembolso;

import java.time.OffsetDateTime;

public record ReembolsoResponse(
        Long id,
        String estado,
        Long montoCentavos,
        String gatewayRef,
        String errorMensaje,
        OffsetDateTime creadoEn,
        OffsetDateTime confirmadoEn
) {}
