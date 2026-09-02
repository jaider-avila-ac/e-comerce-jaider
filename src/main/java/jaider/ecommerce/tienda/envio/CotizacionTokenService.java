package jaider.ecommerce.tienda.envio;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
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

    public String firmar(Long usrId, Long tndId, Long direccionId, String huellaCarrito, CotizacionFirmada c) {
        Date now = new Date();
        return Jwts.builder()
                .claim("typ", TYP)
                .claim("usr_id", usrId)
                .claim("tnd_id", tndId)
                .claim("dir_id", direccionId)
                .claim("carrito_hash", huellaCarrito)
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
    public Optional<CotizacionFirmada> verificar(String token, Long usrId, Long tndId, Long direccionId,
                                                  String huellaCarrito) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!TYP.equals(c.get("typ"))) return Optional.empty();
            if (!usrId.equals(numero(c.get("usr_id"))) || !tndId.equals(numero(c.get("tnd_id")))
                    || !direccionId.equals(numero(c.get("dir_id")))
                    || !MessageDigest.isEqual(bytes(huellaCarrito), bytes((String) c.get("carrito_hash")))) {
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

    /** Huella de todo lo que puede modificar una tarifa: paquetes armados y valor declarado. */
    public String huellaCotizacion(List<PaqueteCalculado> paquetes, long subtotalCentavos, String destino) {
        StringBuilder canonical = new StringBuilder().append(subtotalCentavos).append('|').append(normalizar(destino));
        paquetes.stream().sorted(Comparator.comparing(PaqueteCalculado::empaqueId)).forEach(p -> canonical
                .append('|').append(p.empaqueId()).append(':').append(p.cantidad())
                .append(':').append(p.pesoGramosPorUnidad()).append(':').append(p.largoCm())
                .append(':').append(p.anchoCm()).append(':').append(p.altoCm()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public String claveDestino(String nombre, String telefono, String direccion, String municipio,
                               String departamento, String codigoPostal) {
        return String.join("|", normalizar(nombre), normalizar(telefono), normalizar(direccion),
                normalizar(municipio), normalizar(departamento), normalizar(codigoPostal));
    }

    private String normalizar(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private Long numero(Object o) {
        if (o instanceof Number n) return n.longValue();
        return null;
    }
}
