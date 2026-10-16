package jaider.ecommerce.tienda.integracion;

import jaider.ecommerce.tienda.superadmin.CampoEstadoResponse;

import java.util.List;

/** Respuesta de GET /api/v1/tienda/wompi: estado de las 4 llaves + la URL de webhook que el
 *  admin de la tienda debe pegar en su propio dashboard de Wompi (mismo dato que ve el
 *  superadmin en TiendaDetalleResponse, para que ambos lo muestren igual). */
public record TiendaWompiEstadoResponse(
        List<CampoEstadoResponse> campos,
        String webhookUrl
) {}
