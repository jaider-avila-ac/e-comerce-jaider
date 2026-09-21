package jaider.ecommerce.analitica;

import jaider.ecommerce.auth.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/visitas")
@RequiredArgsConstructor
public class VisitaController {

    private final VisitaService service;
    private final JwtService jwtService;

    @PostMapping
    public ResponseEntity<Void> registrar(
            @RequestBody VisitaEventoRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        service.registrar(req, extractUsrIdSiHayToken(authHeader));
        return ResponseEntity.accepted().build();
    }

    // A diferencia de EventoController.extractIds, acá NUNCA se exige el token — este endpoint
    // es genuinamente público, sirve tanto a invitados como a clientes logueados. Si hay un
    // Bearer token válido se aprovecha para saber que ES un cliente registrado; cualquier otro
    // caso (sin token, token vencido/inválido) se trata sin más como visitante anónimo, nunca
    // se rechaza la solicitud por eso.
    private Long extractUsrIdSiHayToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) return null;
        return jwtService.extractUsrId(token);
    }
}
