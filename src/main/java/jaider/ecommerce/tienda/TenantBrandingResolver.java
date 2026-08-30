package jaider.ecommerce.tienda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Arma el {@link TenantBrandingContext} de una tienda a partir de su fila en `tiendas`. */
@Component
@RequiredArgsConstructor
public class TenantBrandingResolver {

    private final TiendaRepository tiendaRepo;

    public TenantBrandingContext resolve(Long tndId) {
        Tienda t = tiendaRepo.findById(tndId)
                .orElseThrow(() -> new IllegalStateException("No se pudo resolver el branding: tienda " + tndId + " no existe"));
        return new TenantBrandingContext(
                t.getId(),
                t.getNombre(),
                t.getLogoUrl(),
                t.getSitioWeb(),
                t.getEmailContacto(),
                t.getWhatsappPrincipal(),
                t.getColorPrincipal(),
                t.getRazonSocial(),
                t.getNit());
    }
}
