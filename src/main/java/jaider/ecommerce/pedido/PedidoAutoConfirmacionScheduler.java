package jaider.ecommerce.pedido;

import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recorre cada tienda activa y confirma automáticamente los pedidos entregados que llevan
 *  más de 72h sin que el cliente los marque como recibidos — ver PedidoAutoConfirmacionService. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PedidoAutoConfirmacionScheduler {

    private final PedidoAutoConfirmacionService service;

    @Scheduled(cron = "0 0 * * * *") // cada hora, en punto
    public void ejecutar() {
        for (Long tndId : service.tenantsActivos()) {
            try {
                TenantContext.set(tndId.toString());
                int actualizados = service.confirmarVencidos(tndId);
                if (actualizados > 0) {
                    log.info("[Auto-confirmación] tienda {}: {} pedido(s) confirmados automáticamente tras 72h",
                            tndId, actualizados);
                }
            } catch (Exception e) {
                log.warn("[Auto-confirmación] error en tienda {}: {}", tndId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
