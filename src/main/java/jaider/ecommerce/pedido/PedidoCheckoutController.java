package jaider.ecommerce.pedido;

import jaider.ecommerce.auth.jwt.JwtService;
import jaider.ecommerce.pedido.devolucion.CodigoRastreoDevolucionRequest;
import jaider.ecommerce.pedido.devolucion.SolicitudDevolucionRequest;
import jaider.ecommerce.pedido.devolucion.SolicitudDevolucionResponse;
import jaider.ecommerce.pedido.devolucion.SolicitudDevolucionService;
import jaider.ecommerce.shared.interceptor.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/pedidos")
@RequiredArgsConstructor
public class PedidoCheckoutController {

    private final PedidoCheckoutService checkoutService;
    private final PedidoCreacionService pedidoCreacionService;
    private final SolicitudDevolucionService devolucionService;
    private final JwtService jwtService;

    /** Checkout hospedado: crea el pedido y devuelve la URL de la ventana de pago de Wompi. */
    @PostMapping("/checkout")
    public CheckoutResponse checkout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CheckoutRequest req) {
        Long[] ids = extractIds(authHeader);
        return checkoutService.iniciarCheckoutHospedado(ids[0], ids[1], req);
    }

    /** Checkout con tarjeta tokenizada: cobra de inmediato, sin ventana de Wompi. */
    @PostMapping("/checkout/tarjeta")
    public PagoTarjetaResponse checkoutTarjeta(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CheckoutTarjetaRequest req) {
        Long[] ids = extractIds(authHeader);
        return checkoutService.pagarConTarjeta(ids[0], ids[1], req);
    }

    /** Estado del pedido y su último pago — para hacer polling tras el checkout. */
    @GetMapping
    public List<Map<String, Object>> misCompras(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long[] ids = extractIds(authHeader);
        return pedidoCreacionService.listarComprasAprobadas(ids[0], ids[1]);
    }

    @GetMapping("/{numero}")
    public Map<String, Object> estado(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String numero) {
        Long[] ids = extractIds(authHeader);
        return pedidoCreacionService.consultarEstado(ids[0], ids[1], numero);
    }

    /** El cliente confirma que ya recibió su pedido (con o sin envío ya marcado como
     *  "entregado" por el admin — no hay integración con transportadoras). */
    @PostMapping("/{numero}/confirmar-recibido")
    public void confirmarRecibido(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String numero) {
        Long[] ids = extractIds(authHeader);
        pedidoCreacionService.confirmarRecibido(ids[0], ids[1], numero);
    }

    // ─── Devoluciones ────────────────────────────────────────────────────

    @PostMapping("/{numero}/devolucion")
    public SolicitudDevolucionResponse crearDevolucion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String numero,
            @RequestBody SolicitudDevolucionRequest req) {
        Long[] ids = extractIds(authHeader);
        return devolucionService.crear(ids[0], ids[1], numero, req);
    }

    @GetMapping("/{numero}/devolucion")
    public ResponseEntity<SolicitudDevolucionResponse> verDevolucion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String numero) {
        Long[] ids = extractIds(authHeader);
        return devolucionService.obtenerPorPedido(ids[0], ids[1], numero)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PatchMapping("/{numero}/devolucion/codigo-rastreo")
    public SolicitudDevolucionResponse registrarCodigoRastreoDevolucion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String numero,
            @RequestBody CodigoRastreoDevolucionRequest req) {
        Long[] ids = extractIds(authHeader);
        return devolucionService.registrarCodigoRastreo(ids[0], ids[1], numero, req.codigo());
    }

    @PostMapping("/{numero}/devolucion/cancelar")
    public void cancelarDevolucion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String numero) {
        Long[] ids = extractIds(authHeader);
        devolucionService.cancelar(ids[0], ids[1], numero);
    }

    private Long[] extractIds(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token requerido");
        }
        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido");
        }
        Long usrId = jwtService.extractUsrId(token);
        Long tndId = jwtService.extractTndId(token);
        if (usrId == null || tndId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token sin usr_id");
        }
        TenantContext.set(tndId.toString());
        return new Long[]{usrId, tndId};
    }
}
