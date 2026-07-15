package jaider.ecommerce.pedido.devolucion;

import java.util.List;

/** fotoUrls: URLs ya subidas a Cloudinary vía POST /api/v1/upload/devolucion —
 *  el cliente sube las fotos primero, y solo al confirmar se crea la solicitud. */
public record SolicitudDevolucionRequest(String motivo, List<String> fotoUrls) {}
