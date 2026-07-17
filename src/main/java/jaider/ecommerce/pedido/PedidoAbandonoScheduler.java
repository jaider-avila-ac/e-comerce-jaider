package jaider.ecommerce.pedido;

import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Recorre cada tienda activa y cancela automáticamente los pedidos que llevan más de 2h
 *  en "pendiente_pago" sin que el pago se confirme — ver PedidoAbandonoService. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PedidoAbandonoScheduler {

    private final PedidoAbandonoService service;

    @Scheduled(cron = "0 30 * * * *") // cada hora, a los :30 (desfasado de la auto-confirmación, que corre en punto)
    public void ejecutar() {
        for (Long tndId : service.tenantsActivos()) {
            try {
                TenantContext.set(tndId.toString());
                List<Long> pendientes = service.pedidosAbandonados(tndId);
                for (Long pedId : pendientes) {
                    try {
                        service.cancelarAbandonado(pedId);
                    } catch (Exception e) {
                        log.warn("[Abandono] error cancelando pedido {}: {}", pedId, e.getMessage());
                    }
                }
                if (!pendientes.isEmpty()) {
                    log.info("[Abandono] tienda {}: {} pedido(s) cancelados por falta de pago", tndId, pendientes.size());
                }
            } catch (Exception e) {
                log.warn("[Abandono] error en tienda {}: {}", tndId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
