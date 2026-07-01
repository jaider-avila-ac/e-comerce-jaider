package jaider.ecommerce.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ResendEmailService {

    @Value("${resend.api-key}")
    private String apiKey;

    @Value("${resend.from}")
    private String from;

    @Value("${email.override:}")
    private String emailOverride;

    public void sendVerification(String to, String nombre, String code) {
        String recipient = override(to);
        String html = """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;background:#fff">
              <h2 style="margin:0 0 8px;color:#111;font-size:20px">Hola%s</h2>
              <p style="color:#555;font-size:15px;margin:0 0 24px">Tu código de verificación para Calzacaribe es:</p>
              <div style="font-size:40px;font-weight:900;letter-spacing:10px;color:#111;padding:20px 0;text-align:center;background:#f5f5f5;border-radius:12px">%s</div>
              <p style="color:#888;font-size:13px;margin-top:20px">Este código expira en <strong>5 minutos</strong>. Si no solicitaste este código, ignora este mensaje.</p>
            </div>
            """.formatted(nombre != null && !nombre.isBlank() ? ", " + nombre : "", code);
        send(recipient, "Tu código de verificación — Calzacaribe", html);
    }

    public void sendPasswordReset(String to, String nombre, String code) {
        String recipient = override(to);
        String html = """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;background:#fff">
              <h2 style="margin:0 0 8px;color:#111;font-size:20px">Hola%s</h2>
              <p style="color:#555;font-size:15px;margin:0 0 24px">Tu código para restablecer la contraseña de Calzacaribe es:</p>
              <div style="font-size:40px;font-weight:900;letter-spacing:10px;color:#111;padding:20px 0;text-align:center;background:#f5f5f5;border-radius:12px">%s</div>
              <p style="color:#888;font-size:13px;margin-top:20px">Este código expira en <strong>5 minutos</strong>. Si no lo solicitaste, puedes ignorar este mensaje.</p>
            </div>
            """.formatted(nombre != null && !nombre.isBlank() ? ", " + nombre : "", code);
        send(recipient, "Restablecer contraseña — Calzacaribe", html);
    }

    private String override(String original) {
        return (emailOverride != null && !emailOverride.isBlank()) ? emailOverride : original;
    }

    private void send(String to, String subject, String html) {
        try {
            RestClient.create()
                    .post()
                    .uri("https://api.resend.com/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "from", from,
                            "to", List.of(to),
                            "subject", subject,
                            "html", html
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[EMAIL] enviado a={} asunto={}", to, subject);
        } catch (Exception e) {
            log.error("[EMAIL] error enviando a={}: {}", to, e.getMessage());
        }
    }
}
