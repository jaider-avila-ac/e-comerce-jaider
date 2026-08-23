package jaider.ecommerce.notificacion.event;

import jaider.ecommerce.notificacion.NotificacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Canal lateral de notificaciones: cada handler corre @Async y solo después de que la transacción
 * de negocio que publicó el evento ya hizo commit (AFTER_COMMIT). Así, publicar un evento aquí
 * nunca puede retrasar, bloquear ni hacer fallar el flujo de negocio que lo originó.
 */
@Component
@RequiredArgsConstructor
public class NotificacionEventListener {

    private final NotificacionService notificacionService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPedidoPagado(PedidoPagadoEvent event) {
        notificacionService.notificarAdmin(
                event.tndId(), "pedido_nuevo",
                "Nuevo pedido pagado",
                "El pedido " + event.numero() + " fue pagado y está listo para preparar.",
                "pedido", event.pedId());

        notificacionService.avisarNuevoPedidoPorEmail(event.tndId(), event.pedId(), event.numero());
        notificacionService.avisarConfirmacionCompraAlCliente(event.tndId(), event.pedId(), event.numero());
    }

    // Aviso informativo — desde que confirmarPedido() cancela y reembolsa automáticamente cuando
    // falta stock (ver PagoConfirmacionService, F-09 de la auditoría), ya no requiere acción
    // manual: el admin solo necesita enterarse de que pasó, para reabastecer si aplica.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertaStock(AlertaStockEvent event) {
        notificacionService.notificarAdmin(
                event.tndId(), "alerta_stock",
                "Pedido cancelado por falta de stock — " + event.numero(),
                "El pedido " + event.numero() + " se pagó, pero no había stock suficiente para uno o más artículos. Se canceló y reembolsó automáticamente.",
                "pedido", event.pedId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertaStockResuelta(AlertaStockResueltaEvent event) {
        notificacionService.notificarAdmin(
                event.tndId(), "alerta_stock_resuelta",
                "Alerta de stock resuelta",
                "La alerta de stock del pedido " + event.numero() + " fue marcada como resuelta.",
                "pedido", event.pedId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPedidoEstadoCambiado(PedidoEstadoCambiadoEvent event) {
        // La cancelación por admin publica además PedidoCanceladoEvent con el motivo/nota reales
        // (ver onPedidoCancelado) — evita duplicar con este aviso genérico de cambio de estado.
        if ("cancelado".equals(event.estado())) return;

        boolean pagado = "pagado".equals(event.estado());
        String tipo = pagado ? "pedido_confirmado" : "cambio_estado";
        String titulo = pagado
                ? "Pedido confirmado"
                : "Tu pedido " + event.numero() + " cambió de estado";
        String mensaje = pagado
                ? "Hemos confirmado tu pedido " + event.numero() + ". Pronto comenzará el proceso de envío."
                : "Tu pedido " + event.numero() + " ahora está: " + event.estado();

        notificacionService.notificarCliente(
                event.tndId(), event.usrId(), tipo, titulo, mensaje,
                "pedido", event.pedId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPedidoCancelado(PedidoCanceladoEvent event) {
        String mensaje = "Tu pedido " + event.numero() + " fue cancelado. Motivo: " + event.motivoLabel() + "."
                + (event.nota() != null && !event.nota().isBlank() ? " " + event.nota() : "");

        notificacionService.notificarCliente(
                event.tndId(), event.usrId(), "pedido_cancelado",
                "Tu pedido " + event.numero() + " fue cancelado", mensaje,
                "pedido", event.pedId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockDisponible(StockDisponibleEvent event) {
        notificacionService.notificarInteresadosEnProducto(event.tndId(), event.prdId(), event.nombreProducto());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOferta(OfertaEvent event) {
        notificacionService.notificarOfertaATodos(
                event.tndId(), event.titulo(), event.cuerpo(), event.entidadTipo(), event.entidadId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPreguntaCreada(PreguntaCreadaEvent event) {
        notificacionService.notificarAdmin(
                event.tndId(), "pregunta_nueva",
                "Nueva pregunta sobre " + event.prdNombre(),
                event.clienteNombre() + " preguntó por \"" + event.prdNombre() + "\".",
                "pregunta", event.pregId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPreguntaRespondida(PreguntaRespondidaEvent event) {
        notificacionService.notificarCliente(
                event.tndId(), event.usrId(), "pregunta_respondida",
                "Respondimos tu pregunta",
                "Ya respondimos tu pregunta sobre \"" + event.prdNombre() + "\".",
                "producto", event.prdId());
    }
}
