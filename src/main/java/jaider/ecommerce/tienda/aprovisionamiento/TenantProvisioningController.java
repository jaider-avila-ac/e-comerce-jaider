package jaider.ecommerce.tienda.aprovisionamiento;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Alta de una tienda nueva (§15) — operación GLOBAL, no pertenece a ningún tenant existente, así
 * que no la protege el JWT normal de un admin de tienda (no existe todavía un flujo real de
 * superadmin, ver [[multitenant_plan]]). En su lugar exige una llave compartida
 * (PROVISIONING_API_KEY) que solo conoce quien opera la plataforma — pensada para usarse rara
 * vez, a mano, no como un endpoint de autoservicio.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/aprovisionamiento")
@RequiredArgsConstructor
public class TenantProvisioningController {

    private final TenantProvisioningService service;

    @Value("${provisioning.api-key:}")
    private String provisioningApiKey;

    @PostMapping("/tiendas")
    public TenantProvisioningResult crear(@RequestHeader(value = "X-Provisioning-Key", required = false) String key,
                                           @Valid @RequestBody TenantProvisioningRequest req) {
        if (provisioningApiKey == null || provisioningApiKey.isBlank()) {
            // Nunca "abrir" el endpoint por accidente si a nadie se le ocurrió configurar la
            // llave — sin PROVISIONING_API_KEY, esta operación queda deshabilitada del todo.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Aprovisionamiento no configurado (falta PROVISIONING_API_KEY)");
        }
        if (key == null || !provisioningApiKey.equals(key)) {
            log.warn("[Aprovisionamiento] Intento con llave inválida o ausente");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Llave de aprovisionamiento inválida");
        }
        return service.provisionar(req);
    }
}
