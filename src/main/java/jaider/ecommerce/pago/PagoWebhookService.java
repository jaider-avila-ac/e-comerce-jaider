package jaider.ecommerce.pago;

import jaider.ecommerce.pago.dto.WebhookTransactionEvent;
import jaider.ecommerce.pago.service.PagoConfirmacionService;
import jaider.ecommerce.pago.service.PaymentGateway;
import jaider.ecommerce.pago.wompi.WompiGatewayFactory;
import jaider.ecommerce.pedido.PedidoRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Procesa los webhooks de transacción de Wompi.
 *
 * El webhook llega sin JWT ni header X-Tenant-Id, así que el tenant se resuelve a partir de la
 * referencia (formato "ECM-{tndId}-{pedId}-{uuid}", ver WompiService.generarReferencia) — pero a
 * diferencia del resto de la app, ACÁ el orden importa muchísimo (§7.3 del plan): hay que
 * resolver el tenant ANTES de verificar la firma, porque cada tienda tiene su propia events key
 * y sin saber cuál tienda es no hay con qué llave verificar. Se lee la referencia del payload
 * SIN CONFIAR en ella todavía (solo para decidir qué credenciales cargar) — la firma es lo único
 * que la convierte en confiable, y solo después de verificada se toca cualquier tabla con RLS
 * (fn_current_tnd_id() sería NULL sin esto, y las políticas bloquearían todo igual).
 *
 * procesarWebhook() es una única transacción: si confirmarPedido() falla, Spring revierte también
 * registrarAprobado() (quedan en la misma transacción física, a diferencia de pagarConTarjeta que
 * invoca esos mismos métodos sin transacción propia — ver PagoConfirmacionService). Es intencional:
 * acá el dinero ya lo capturó Wompi de forma independiente, así que un rollback total es seguro y
 * deja que el reintento automático de webhooks de Wompi reprocese todo desde cero.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PagoWebhookService {

    private final WompiGatewayFactory gatewayFactory;
    private final PagoRepository pagoRepo;
    private final EventoPagoRepository eventoPagoRepo;
    private final PedidoRepository pedidoRepo;
    private final TenantSupport tenantSupport;
    private final PagoConfirmacionService confirmacionService;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void procesarWebhook(Map<String, Object> evento) {
        String referenciaCruda = extraerReferenciaSinVerificar(evento);
        Long tndId = extraerTndId(referenciaCruda);
        if (tndId == null) {
            log.warn("Webhook: no se pudo resolver el tenant desde la referencia {}", referenciaCruda);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Referencia inválida");
        }

        PaymentGateway gateway;
        try {
            gateway = gatewayFactory.forTenant(tndId);
        } catch (IllegalStateException e) {
            // Alias inexistente o credenciales de esa tienda no configuradas — no es un problema
            // de firma, es de aprovisionamiento. Tampoco se procesa, pero es un 400 distinto al
            // de firma inválida para que quede claro en los logs cuál de los dos pasó.
            log.error("Webhook: no se pudieron resolver credenciales de Wompi para tenant {}: {}", tndId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tienda no configurada para pagos");
        }

        if (!gateway.verificarWebhook(evento)) {
            log.warn("Webhook Wompi rechazado: firma inválida (tenant={})", tndId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Firma inválida");
        }

        WebhookTransactionEvent tx = gateway.parsearWebhook(evento);
        if (tx == null || !"transaction.updated".equals(tx.eventType())) return;

        // La referencia YA VERIFICADA debe seguir señalando al mismo tenant que se usó para
        // cargar las credenciales — mismo campo, pero server-side conviene no asumirlo sin
        // comprobarlo (defensa adicional si algún día cambia cómo se arma/parsea la referencia).
        if (!tndId.equals(extraerTndId(tx.referencia()))) {
            log.warn("Webhook: la referencia verificada ({}) no corresponde al tenant resuelto antes de firmar ({})",
                    tx.referencia(), tndId);
            return;
        }

        TenantContext.set(tndId.toString());
        tenantSupport.applyTenant(em);

        Pago pago = pagoRepo.findByReferencia(tx.referencia()).orElse(null);
        if (pago == null) {
            log.warn("Webhook: referencia {} no encontrada en tenant {}", tx.referencia(), tndId);
            return;
        }

        // §7.3 punto 6: comparar referencia, monto y moneda ANTES de aplicar cualquier cambio de
        // estado — una firma válida certifica que el evento es de Wompi, no que corresponda al
        // monto/moneda que nosotros esperábamos para ese pago.
        if (tx.amountCentavos() != null && !tx.amountCentavos().equals(pago.getMontoCentavos())) {
            log.warn("Webhook: monto no coincide para ref {} (esperado={} recibido={}) — ignorado",
                    tx.referencia(), pago.getMontoCentavos(), tx.amountCentavos());
            return;
        }
        if (tx.currency() != null && !tx.currency().equals(pago.getMoneda())) {
            log.warn("Webhook: moneda no coincide para ref {} (esperado={} recibido={}) — ignorado",
                    tx.referencia(), pago.getMoneda(), tx.currency());
            return;
        }

        if ("APPROVED".equals(pago.getEstado())) {
            log.info("Webhook duplicado para ref {} — ya estaba APPROVED, ignorado", tx.referencia());
            return;
        }

        registrarEvento(pago, tx, evento);

        String status = tx.status() != null ? tx.status() : "";
        switch (status) {
            case "APPROVED" -> {
                // Sin try/catch a propósito: si confirmarPedido() falla, debe revertirse todo
                // (incluido registrarAprobado) para que el reintento de Wompi reprocese limpio.
                confirmacionService.registrarAprobado(pago.getId(), tx.gatewayTxId(), tx.metodoPago(), evento);
                confirmacionService.confirmarPedido(pago.getId());
            }
            case "VOIDED" -> {
                confirmacionService.registrarRechazado(pago.getId(), "VOIDED", null,
                        tx.gatewayTxId(), tx.metodoPago(), evento);
                pedidoRepo.updateEstado(pago.getPedId(), "cancelado");
            }
            case "DECLINED", "ERROR" -> {
                String estado = "DECLINED".equals(status) ? "DECLINED" : "ERROR";
                confirmacionService.registrarRechazado(pago.getId(), estado, extraerStatusMessage(evento),
                        tx.gatewayTxId(), tx.metodoPago(), evento);
                confirmacionService.cancelarPedidoNoAprobado(pago.getId());
            }
            default -> log.info("Webhook con estado no manejado: {} (ref {})", status, tx.referencia());
        }
    }

    private void registrarEvento(Pago pago, WebhookTransactionEvent tx, Map<String, Object> payload) {
        EventoPago evt = new EventoPago();
        evt.setPagId(pago.getId());
        evt.setPedId(pago.getPedId());
        evt.setTipo(tx.eventType());
        evt.setProveedorId(tx.gatewayTxId());
        evt.setPayload(payload);
        eventoPagoRepo.save(evt);
    }

    /** Lee SOLO el campo "reference" del payload crudo, sin validar nada — únicamente para saber
     *  qué tienda/credenciales cargar antes de poder verificar la firma de verdad. Nada de lo que
     *  se lea acá debe usarse para tomar decisiones de negocio hasta que verificarWebhook() pase. */
    @SuppressWarnings("unchecked")
    private String extraerReferenciaSinVerificar(Map<String, Object> evento) {
        try {
            var data = (Map<String, Object>) evento.get("data");
            if (data == null) return null;
            var tx = (Map<String, Object>) data.get("transaction");
            if (tx == null) return null;
            Object ref = tx.get("reference");
            return ref instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Long extraerTndId(String referencia) {
        if (referencia == null) return null;
        String[] parts = referencia.split("-");
        if (parts.length < 2) return null;
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extraerStatusMessage(Map<String, Object> evento) {
        try {
            var data = (Map<String, Object>) evento.get("data");
            if (data == null) return null;
            var tx = (Map<String, Object>) data.get("transaction");
            if (tx == null) return null;
            Object msg = tx.get("status_message");
            return msg instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }
}
