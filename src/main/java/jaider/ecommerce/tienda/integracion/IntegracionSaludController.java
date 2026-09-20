package jaider.ecommerce.tienda.integracion;

import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Autodiagnóstico de las integraciones (Cloudinary/Resend/Wompi) de LA PROPIA tienda del
 *  admin autenticado — cualquier staff puede consultarlo, solo lee, nunca cobra/envía/sube nada. */
@RestController
@RequestMapping("/api/v1/integraciones")
@RequiredArgsConstructor
public class IntegracionSaludController {

    private final TenantIntegrationHealthService healthService;

    @GetMapping("/salud")
    public List<IntegracionSalud> salud() {
        String tndId = TenantContext.get();
        if (tndId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        }
        return healthService.chequear(Long.parseLong(tndId));
    }
}
