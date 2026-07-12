package jaider.ecommerce.pago.service;

import jaider.ecommerce.pago.dto.CobroTarjetaResultado;
import jaider.ecommerce.pago.dto.WebhookTransactionEvent;
import jaider.ecommerce.pago.dto.WompiAcceptanceTokensDto;

import java.util.Map;
import java.util.Optional;

/**
 * Abstracción de pasarela de pago. Cada proveedor (Wompi, Stripe, etc.) implementa esta interfaz.
 * Los servicios de pedido/checkout inyectan PaymentGateway, nunca la implementación concreta.
 *
 * Para agregar un nuevo proveedor:
 *   1. Crear nueva implementación en pago/{proveedor}/
 *   2. Agregar el valor al enum ProveedorPago
 *   3. Implementar parsearWebhook() para traducir el formato nativo a WebhookTransactionEvent
 */
public interface PaymentGateway {

    /** Referencia única embebiendo tndId y pedId — permite resolver el tenant en el webhook sin JWT. */
    String generarReferencia(Long tndId, Long pedId);

    // ── Checkout hospedado (ventana de Wompi) ─────────────────────────────

    String buildCheckoutUrl(String referencia, long amountCentavos, String currency, String redirectUrl);

    /** Verifica la firma del evento. Retorna false si la firma no es válida. */
    boolean verificarWebhook(Map<String, Object> evento);

    /**
     * Parsea el payload raw del webhook al formato genérico WebhookTransactionEvent.
     * Solo llamar después de que verificarWebhook() retorne true.
     * Retorna null si el evento no es de tipo transacción o no se puede parsear.
     */
    WebhookTransactionEvent parsearWebhook(Map<String, Object> evento);

    /**
     * Consulta el estado actual de una transacción directamente en la pasarela por referencia interna.
     * Útil para reconciliación manual cuando el webhook nunca llega.
     * Retorna vacío si la referencia no existe en la pasarela o si ocurre un error de red.
     */
    Optional<WebhookTransactionEvent> consultarTransaccion(String referencia);

    // ── Pago directo con tarjeta tokenizada (sin ventana de Wompi) ────────
    // Cobro único: la fuente de pago se crea y se cobra en el mismo request, sin persistirla
    // para cobros futuros (acá no hay suscripciones).

    /**
     * Obtiene los tokens de aceptación de Wompi para mostrar al usuario antes de tokenizar su tarjeta.
     * Llama a GET /merchants/{publicKey} sin autenticación.
     */
    WompiAcceptanceTokensDto obtenerTokensAceptacion();

    /**
     * Crea una fuente de pago en Wompi a partir de un token de tarjeta. Requiere llave privada.
     *
     * @return ID de la fuente de pago en Wompi, usado inmediatamente para cobrar (no se persiste).
     * @throws RuntimeException si Wompi rechaza la creación
     */
    Long crearFuentePago(String cardToken, String customerEmail, String acceptanceToken, String personalAuthToken);

    /**
     * Cobra de inmediato usando una fuente de pago recién creada. No redirige al usuario.
     * Retorna estado síncrono; el estado final también puede llegar por webhook (idempotente).
     */
    CobroTarjetaResultado cobrarFuentePago(long paymentSourceId, String customerEmail,
                                            long amountCentavos, String referencia);
}
