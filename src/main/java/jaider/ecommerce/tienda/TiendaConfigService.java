package jaider.ecommerce.tienda;

import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TiendaConfigService {

    private final TiendaRepository repo;

    @Transactional(readOnly = true)
    public TiendaConfigResponse getConfig() {
        return toResponse(currentTienda());
    }

    @Transactional
    public TiendaConfigResponse updateConfig(TiendaConfigRequest req) {
        Tienda tienda = currentTienda();

        if (req.envioGratisActivo() != null) {
            tienda.setEnvioGratisActivo(req.envioGratisActivo());
        }
        if (req.envioGratisDesde() != null) {
            if (req.envioGratisDesde() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El monto no puede ser negativo");
            }
            tienda.setEnvioGratisDesdeCentavos(req.envioGratisDesde() * 100L);
        }
        if (req.envioCosto() != null) {
            if (req.envioCosto() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El costo de envío no puede ser negativo");
            }
            tienda.setEnvioCostoCentavos(req.envioCosto() * 100L);
        }
        if (req.dominioStaff() != null) {
            String dominio = req.dominioStaff().trim().toLowerCase();
            if (dominio.startsWith("@")) dominio = dominio.substring(1);
            tienda.setDominioStaff(dominio.isBlank() ? null : dominio);
        }
        if (req.emailNotificacionPedidos() != null) {
            String email = req.emailNotificacionPedidos().trim();
            if (!email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Correo inválido");
            }
            tienda.setEmailNotificacionPedidos(email.isBlank() ? null : email);
        }

        repo.save(tienda);
        return toResponse(tienda);
    }

    private Tienda currentTienda() {
        String tndId = TenantContext.get();
        if (tndId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        }
        return repo.findById(Long.parseLong(tndId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
    }

    private TiendaConfigResponse toResponse(Tienda tienda) {
        return new TiendaConfigResponse(
                tienda.isEnvioGratisActivo(),
                tienda.getEnvioGratisDesdeCentavos() / 100L,
                tienda.getEnvioCostoCentavos() / 100L,
                tienda.getDominioStaff(),
                tienda.getEmailNotificacionPedidos()
        );
    }
}
