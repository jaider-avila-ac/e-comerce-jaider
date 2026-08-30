package jaider.ecommerce.pago.wompi;

import jaider.ecommerce.pago.service.PaymentGateway;
import jaider.ecommerce.tienda.integracion.TenantIntegrationResolver;
import jaider.ecommerce.tienda.integracion.WompiCredentials;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * PaymentGatewayFactory (§7 del plan) — el único punto del código que sabe cómo construir un
 * {@link PaymentGateway} listo para operar EN NOMBRE de una tienda concreta. Todo lo que antes
 * inyectaba {@code WompiService} directo ahora inyecta esta factory y pide
 * {@code forTenant(tndId)} al principio del método, antes de la primera llamada a la pasarela.
 *
 * Si mañana se agrega OnePay u otro proveedor, este es el lugar para decidir cuál construir
 * según {@code tiendas.tnd_pago_proveedor} (columna que aún no existe — hoy solo hay Wompi).
 */
@Component
@RequiredArgsConstructor
public class WompiGatewayFactory {

    private final TenantIntegrationResolver integrationResolver;

    // HttpClient es seguro para compartir entre hilos y tenants — no guarda credenciales, cada
    // request las lleva en su propio header Authorization armado por la instancia de WompiService
    // que sí es específica de la tienda.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public PaymentGateway forTenant(Long tndId) {
        WompiCredentials credentials = integrationResolver.paymentCredentials(tndId);
        return new WompiService(credentials, httpClient);
    }
}
