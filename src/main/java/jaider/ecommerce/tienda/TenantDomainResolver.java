package jaider.ecommerce.tienda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Resuelve el tenant de una solicitud pública a partir de un dominio (§5 del plan) — la fuente
 * preferida sobre X-Tenant-Id para rutas públicas, porque un dominio no lo elige el navegador
 * del cliente, lo controla quien administra el DNS/proxy de esa tienda.
 *
 * Solo normaliza y busca; NO decide de dónde sacar el Host real (eso depende de qué headers de
 * proxy sean confiables en cada request — ver TenantInterceptor, que ya sigue el mismo patrón
 * manual de X-Forwarded-* que el resto del proyecto, ej. UsuarioAuthController.clientIp()).
 */
@Component
@RequiredArgsConstructor
public class TenantDomainResolver {

    private final TiendaDominioRepository dominioRepo;

    public Optional<Long> resolveTenantId(String hostHeader) {
        String normalizado = normalizar(hostHeader);
        if (normalizado == null) return Optional.empty();
        return dominioRepo.findByDominioAndActivoTrue(normalizado).map(TiendaDominio::getTndId);
    }

    private String normalizar(String host) {
        if (host == null || host.isBlank()) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        int colon = h.indexOf(':'); // descarta el puerto (ej. "localhost:5174")
        if (colon >= 0) h = h.substring(0, colon);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1); // FQDN con punto final
        return h.isBlank() ? null : h;
    }
}
