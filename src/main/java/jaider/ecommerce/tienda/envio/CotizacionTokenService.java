package jaider.ecommerce.tienda.envio;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Firma y verifica la cotización real que el cliente ve en el carrito — corrección de auditoría
 * (2026-09-01, tercera vuelta): antes, el carrito mostraba un precio (transportadora, servicio,
 * costo) pero el checkout volvía a cotizar desde cero al crear el pedido, sin ningún vínculo
 * entre ambos. Si la tarifa cambiaba o respondía otra transportadora entre una llamada y otra, el
 * cliente autorizaba el pago viendo un valor y terminaba pagando otro.
 *
 * Con esto, {@link EnvioCotizacionService} firma un token opaco con la cotización EXACTA que le
 * mostró al cliente (carrier/servicio/precio/tiempo estimado), y {@code PedidoCreacionService}
 * exige ese mismo token al crear el pedido — nunca vuelve a llamar a Envia en ese momento, así
 * que lo que se cobra es matemáticamente lo mismo que lo que se mostró. Expira a los 15 minutos
 * (tiempo generoso para terminar un checkout, corto para no poder revenderse ni acumular).
 *
 * Reutiliza el mismo secreto que JwtService (jwt.secret) — son tokens JWT normales, solo con
 * claims distintos; el claim "typ" evita que un JWT de sesión (login) se confunda con uno de
 * cotización o viceversa.
 */
@Service
public class CotizacionTokenService {

    private static final String TYP = "cotizacion_envio";
    private static final long TTL_MS = 15 * 60 * 1000L; // 15 minutos

    private final SecretKey key;

    public CotizacionTokenService(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public record CotizacionFirmada(String carrier, String servicioCodigo, String servicioDescripcion,
                                     String tiempoEstimado, long precioCentavos, boolean estimado) {}

    public String firmar(Long usrId, Long direccionId, CotizacionFirmada c) {
        Date now = new Date();
        return Jwts.builder()
                .claim("typ", TYP)
                .claim("usr_id", usrId)
                .claim("dir_id", direccionId)
                .claim("carrier", c.carrier())
                .claim("servicio_codigo", c.servicioCodigo())
                .claim("servicio_desc", c.servicioDescripcion())
                .claim("tiempo_estimado", c.tiempoEstimado())
                .claim("precio_centavos", c.precioCentavos())
                .claim("estimado", c.estimado())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TTL_MS))
                .signWith(key)
                .compact();
    }

    /** Vacío si el token es inválido, venció, o no corresponde a este usuario/dirección exactos
     *  — nunca lanza, el llamador decide qué mensaje darle al cliente (siempre "vuelve a tu
     *  carrito y confirma el precio actualizado", nunca detalles técnicos). */
    public Optional<CotizacionFirmada> verificar(String token, Long usrId, Long direccionId) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!TYP.equals(c.get("typ"))) return Optional.empty();
            if (!usrId.equals(numero(c.get("usr_id"))) || !direccionId.equals(numero(c.get("dir_id")))) {
                return Optional.empty();
            }
            return Optional.of(new CotizacionFirmada(
                    (String) c.get("carrier"),
                    (String) c.get("servicio_codigo"),
                    (String) c.get("servicio_desc"),
                    (String) c.get("tiempo_estimado"),
                    ((Number) c.get("precio_centavos")).longValue(),
                    Boolean.TRUE.equals(c.get("estimado"))));
        } catch (JwtException | IllegalArgumentException | ClassCastException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private Long numero(Object o) {
        if (o instanceof Number n) return n.longValue();
        return null;
    }
}
