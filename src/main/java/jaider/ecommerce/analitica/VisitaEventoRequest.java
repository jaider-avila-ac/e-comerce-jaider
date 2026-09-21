package jaider.ecommerce.analitica;

/**
 * Evento de tráfico capturado desde el storefront — a diferencia de EventoRequest (que exige un
 * usuario logueado, ver EventoController), este SIEMPRE se acepta sin sesión: visitorId es un
 * identificador anónimo persistente (localStorage del navegador), nunca un dato personal.
 */
public record VisitaEventoRequest(
        String visitorId,
        String tipo,          // 'pagina_vista' | 'vista_producto' | 'agregar_carrito' — ver VisitaService.TIPOS_VALIDOS
        String entidadTipo,   // nullable, ej. "producto"
        Long entidadId,       // nullable, ej. el id del producto visto
        String ruta,          // nullable, ej. "/producto/vestido-midi-rayas-crema"
        String dispositivo    // nullable, "movil" | "escritorio"
) {}
