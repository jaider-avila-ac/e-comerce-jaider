package jaider.ecommerce.tienda.superadmin;

/** Los 4 campos son opcionales a propósito (decisión explícita del usuario, 2026-10-15): tanto el
 *  superadmin como el propio admin de la tienda (ver TiendaWompiController) pueden actualizar
 *  UNA sola llave sin reenviar las demás — cualquier campo en blanco/null se ignora y conserva el
 *  valor ya guardado (ver TiendaWompiCredencialesService.guardar). */
public record WompiCredencialesRequest(
        String publicKey,
        String privateKey,
        String integrityKey,
        String eventsKey
) {}
