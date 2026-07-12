package jaider.ecommerce.pago;

import jaider.ecommerce.pago.dto.WebhookTransactionEvent;
import jaider.ecommerce.pago.service.PagoConfirmacionService;
import jaider.ecommerce.pago.service.PaymentGateway;
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
 * referencia (formato "CZC-{tndId}-{pedId}-{uuid}", ver WompiService.generarReferencia) ANTES de
 * tocar cualquier tabla con RLS — de lo contrario fn_current_tnd_id() sería NULL y las políticas
 * bloquearían todo.
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

    private final PaymentGateway gateway;
    private final PagoRepository pagoRepo;
    private final EventoPagoRepository eventoPagoRepo;
    private final PedidoRepository pedidoRepo;
    private final TenantSupport tenantSupport;
    private final PagoConfirmacionService confirmacionService;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void procesarWebhook(Map<String, Object> evento) {
        if (!gateway.verificarWebhook(evento)) {
            log.warn("Webhook Wompi rechazado: firma inválida");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Firma inválida");
        }

        WebhookTransactionEvent tx = gateway.parsearWebhook(evento);
        if (tx == null || !"transaction.updated".equals(tx.eventType())) return;

        Long tndId = extraerTndId(tx.referencia());
        if (tndId == null) {
            log.warn("Webhook: no se pudo resolver el tenant desde la referencia {}", tx.referencia());
            return;
        }
        TenantContext.set(tndId.toString());
        tenantSupport.applyTenant(em);

        Pago pago = pagoRepo.findByReferencia(tx.referencia()).orElse(null);
        if (pago == null) {
            log.warn("Webhook: referencia {} no encontrada", tx.referencia());
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
