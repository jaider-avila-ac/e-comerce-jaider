package jaider.ecommerce.auth.superadmin;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Todo lo que un superadmin puede hacer: consultar totales agregados de la plataforma. Nunca
 * datos operativos ni de una tienda en particular — para actuar DENTRO de una tienda hay que
 * entrar con las credenciales de admin propias de esa tienda, no con esta cuenta (decisión
 * explícita del usuario, 2026-08-30). SecurityConfig excluye SUPERADMIN de todo el resto de
 * /api/v1/** — este es el único rincón de la API al que puede entrar.
 */
@RestController
@RequestMapping("/api/v1/superadmin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERADMIN')")
public class SuperadminController {

    private final SuperadminService service;

    @GetMapping("/resumen")
    public SuperadminResumenResponse resumen() {
        return service.resumen();
    }
}
