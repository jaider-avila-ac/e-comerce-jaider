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

    private static final java.util.Set<String> MODOS_ENVIO_VALIDOS = java.util.Set.of("contra_entrega", "fijo");
    private static final java.util.Set<String> AMBIENTES_ENVIA_VALIDOS = java.util.Set.of("sandbox", "produccion");

    private final TiendaRepository repo;

    @Transactional(readOnly = true)
    public TiendaConfigResponse getConfig() {
        return toResponse(currentTienda());
    }

    @Transactional
    public TiendaConfigResponse updateConfig(TiendaConfigRequest req) {
        Tienda tienda = currentTienda();

        if (req.envioModo() != null) {
            String modo = req.envioModo().trim();
            // PLAN_INTEGRACION_ENVIA.md: el esquema ya acepta 'envia' (columna + CHECK de BD),
            // pero el cálculo real es de la Fase 3 — hasta que exista, activarlo dejaría el
            // checkout cobrando el costo fijo en silencio, como si fuera un envío calculado de
            // verdad. Se bloquea acá con un mensaje claro, no con el genérico de "modo inválido".
            if ("envia".equals(modo)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El envío calculado con Envia todavía no está disponible");
            }
            if (!MODOS_ENVIO_VALIDOS.contains(modo)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Modo de envío inválido: " + modo);
            }
            tienda.setEnvioModo(modo);
        }
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
        if (req.emailContacto() != null) {
            String email = req.emailContacto().trim();
            if (!email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Correo de contacto inválido");
            }
            tienda.setEmailContacto(email.isBlank() ? null : email);
        }
        if (req.razonSocial() != null) {
            String v = req.razonSocial().trim();
            tienda.setRazonSocial(v.isBlank() ? null : v);
        }
        if (req.nit() != null) {
            String v = req.nit().trim();
            tienda.setNit(v.isBlank() ? null : v);
        }
        if (req.colorPrincipal() != null) {
            String v = req.colorPrincipal().trim();
            if (!v.isBlank() && !v.matches("^#[0-9A-Fa-f]{6}$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El color debe ser hexadecimal de 6 dígitos, ej. #1A2B3C");
            }
            tienda.setColorPrincipal(v.isBlank() ? null : v);
        }
        if (req.enviaAmbiente() != null) {
            String v = req.enviaAmbiente().trim().toLowerCase();
            if (!AMBIENTES_ENVIA_VALIDOS.contains(v)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ambiente de Envia inválido: " + v);
            }
            tienda.setEnviaAmbiente(v);
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
                tienda.getEnvioModo(),
                tienda.isEnvioGratisActivo(),
                tienda.getEnvioGratisDesdeCentavos() / 100L,
                tienda.getEnvioCostoCentavos() / 100L,
                tienda.getDominioStaff(),
                tienda.getEmailNotificacionPedidos(),
                tienda.getRazonSocial(),
                tienda.getNit(),
                tienda.getEmailContacto(),
                tienda.getColorPrincipal(),
                tienda.getEnviaAmbiente()
        );
    }
}
