package jaider.ecommerce.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generate(String email, String role, Long tndId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .claim("tnd_id", tndId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public String generate(String email, String role, Long tndId, Long usrId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .claim("tnd_id", tndId)
                .claim("usr_id", usrId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        Object raw = parseClaims(token).get("role");
        return raw != null ? raw.toString() : null;
    }

    public Long extractTndId(String token) {
        Object raw = parseClaims(token).get("tnd_id");
        if (raw instanceof Integer i) return i.longValue();
        if (raw instanceof Long l) return l;
        return null;
    }

    public Long extractUsrId(String token) {
        Object raw = parseClaims(token).get("usr_id");
        if (raw instanceof Integer i) return i.longValue();
        if (raw instanceof Long l) return l;
        return null;
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
