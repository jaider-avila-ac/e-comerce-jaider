package jaider.ecommerce.tienda;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Inverso de TenantDomainResolver: dado un tenant, de qué dominio PROPIO depende para construir
 * URLs públicas que ve el admin de esa tienda (webhooks de Wompi/Envia, etc.) — nunca el dominio
 * compartido del backend, salvo que la tienda todavía no tenga ninguno (recién creada).
 *
 * No es el dominio PRINCIPAL (ese es el de marca y puede no servir /api/** en absoluto — ej. Duo
 * Chic: duochicstudio.com es principal pero es hosting estático sin proxy; api.duochicstudio.com
 * es el que de verdad llega al backend), es el marcado sirveApi.
 */
@Component
@RequiredArgsConstructor
public class TiendaApiBaseUrlResolver {

    private final TiendaDominioRepository dominioRepo;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public String resolver(Long tndId) {
        return dominioRepo.findByTndIdAndSirveApiTrueAndActivoTrue(tndId)
                .map(d -> "https://" + d.getDominio())
                .orElse(appBaseUrl);
    }
}
