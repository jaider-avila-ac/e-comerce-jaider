package jaider.ecommerce.pedido.devolucion;

import java.util.List;

/** tipo: "retracto" (cualquier motivo, plazo de 5 días hábiles — art. 47 Ley 1480/2011) o
 *  "defecto" (defecto de fábrica, sin ese plazo corto, requiere inspección de calidad).
 *  fotoUrls: URLs ya subidas a Cloudinary vía POST /api/v1/upload/devolucion —
 *  el cliente sube las fotos primero, y solo al confirmar se crea la solicitud. */
public record SolicitudDevolucionRequest(String tipo, String motivo, List<String> fotoUrls) {}
