package jaider.ecommerce.infra;

import jaider.ecommerce.shared.TenantMetrics;
import jaider.ecommerce.tienda.TenantBrandingContext;
import jaider.ecommerce.tienda.TenantBrandingResolver;
import jaider.ecommerce.tienda.integracion.ResendCredentials;
import jaider.ecommerce.tienda.integracion.TenantIntegrationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResendEmailService {

    private final TenantIntegrationResolver integrationResolver;
    private final TenantBrandingResolver brandingResolver;
    private final TenantMetrics metrics;

    // Override de desarrollo: redirige TODO correo transaccional (verificación, reset, etc.) a
    // esta dirección sin importar el tenant — no es un secreto de integración de ninguna tienda,
    // es una red de seguridad para no mandarle correos reales a clientes durante pruebas, así que
    // se queda como una única variable global (no por tenant).
    @Value("${email.override:}")
    private String emailOverride;

    public void sendVerification(Long tndId, String to, String nombre, String code) {
        TenantBrandingContext b = brandingResolver.resolve(tndId);
        String recipient = override(to);
        String html = """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;background:#fff">
              <h2 style="margin:0 0 8px;color:#111;font-size:20px">Hola%s</h2>
              <p style="color:#555;font-size:15px;margin:0 0 24px">Tu código de verificación para %s es:</p>
              <div style="font-size:40px;font-weight:900;letter-spacing:10px;color:%s;padding:20px 0;text-align:center;background:#f5f5f5;border-radius:12px">%s</div>
              <p style="color:#888;font-size:13px;margin-top:20px">Este código expira en <strong>5 minutos</strong>. Si no solicitaste este código, ignora este mensaje.</p>
              %s
            </div>
            """.formatted(nombre != null && !nombre.isBlank() ? ", " + nombre : "", b.nombreComercial(),
                    b.colorPrincipalODefecto(), code, pie(b));
        send(tndId, recipient, "Tu código de verificación — " + b.nombreComercial(), html);
    }

    public void sendPasswordReset(Long tndId, String to, String nombre, String code) {
        TenantBrandingContext b = brandingResolver.resolve(tndId);
        String recipient = override(to);
        String html = """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;background:#fff">
              <h2 style="margin:0 0 8px;color:#111;font-size:20px">Hola%s</h2>
              <p style="color:#555;font-size:15px;margin:0 0 24px">Tu código para restablecer la contraseña de %s es:</p>
              <div style="font-size:40px;font-weight:900;letter-spacing:10px;color:%s;padding:20px 0;text-align:center;background:#f5f5f5;border-radius:12px">%s</div>
              <p style="color:#888;font-size:13px;margin-top:20px">Este código expira en <strong>5 minutos</strong>. Si no lo solicitaste, puedes ignorar este mensaje.</p>
              %s
            </div>
            """.formatted(nombre != null && !nombre.isBlank() ? ", " + nombre : "", b.nombreComercial(),
                    b.colorPrincipalODefecto(), code, pie(b));
        send(tndId, recipient, "Restablecer contraseña — " + b.nombreComercial(), html);
    }

    /** Resumen de un ítem del pedido, ya en pesos (no centavos), para el correo de confirmación. */
    public record ItemResumenEmail(String nombre, int cantidad, long precioPesos) {}

    /** Confirmación transaccional al comprador — se dispara una sola vez por pedido, justo cuando
     *  el pago queda aprobado (ver PagoConfirmacionService.confirmarPedido → PedidoPagadoEvent,
     *  que solo se publica la primera vez que el pedido pasa de pendiente_pago a pagado). Incluye
     *  el resumen congelado del pedido — ítems, dirección (si aplica), método y total — para que
     *  el cliente tenga constancia sin depender de volver a entrar a la app (RF-031).
     *  direccion puede venir vacío (ventas locales no pasan por acá, pero por si acaso). */
    public void sendConfirmacionCompra(Long tndId, String to, String nombre, String numero,
                                        List<ItemResumenEmail> items, Map<String, Object> direccion,
                                        String metodoPagoLabel, long totalPesos) {
        TenantBrandingContext b = brandingResolver.resolve(tndId);
        String recipient = override(to);

        String itemsHtml = items.stream().map(i -> """
                <tr>
                  <td style="padding:6px 0;color:#333;font-size:14px">%s <span style="color:#999">x%d</span></td>
                  <td style="padding:6px 0;color:#333;font-size:14px;text-align:right;white-space:nowrap">%s</td>
                </tr>
                """.formatted(i.nombre(), i.cantidad(), formatPesos(i.precioPesos() * i.cantidad())))
                .reduce("", String::concat);

        String direccionTexto = direccionTexto(direccion);
        String direccionHtml = direccionTexto.isBlank() ? "" : """
                <p style="color:#555;font-size:14px;margin:20px 0 4px"><strong>Dirección de envío</strong></p>
                <p style="color:#555;font-size:14px;margin:0">%s</p>
                """.formatted(direccionTexto);

        String html = """
                <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;background:#fff">
                  <h2 style="margin:0 0 8px;color:%s;font-size:20px">¡Gracias por tu compra%s!</h2>
                  <p style="color:#555;font-size:15px;margin:0 0 20px">Confirmamos tu pedido <strong>#%s</strong>. Aquí el resumen:</p>
                  <table style="width:100%%;border-collapse:collapse">
                    %s
                    <tr><td colspan="2" style="border-top:1px solid #eee;padding-top:8px"></td></tr>
                    <tr>
                      <td style="padding:6px 0;color:#111;font-size:15px;font-weight:700">Total</td>
                      <td style="padding:6px 0;color:#111;font-size:15px;font-weight:700;text-align:right">%s</td>
                    </tr>
                  </table>
                  <p style="color:#555;font-size:14px;margin:16px 0 0"><strong>Método de pago:</strong> %s</p>
                  %s
                  <p style="color:#888;font-size:13px;margin-top:24px">Te avisaremos por aquí y dentro de tu cuenta cuando tu pedido sea despachado.</p>
                  %s
                </div>
                """.formatted(
                        b.colorPrincipalODefecto(), nombre != null && !nombre.isBlank() ? ", " + nombre : "",
                        numero, itemsHtml, formatPesos(totalPesos),
                        metodoPagoLabel != null ? metodoPagoLabel : "No especificado",
                        direccionHtml, pie(b));

        send(tndId, recipient, "Confirmamos tu pedido " + numero + " — " + b.nombreComercial(), html);
    }

    private String direccionTexto(Map<String, Object> direccion) {
        if (direccion == null) return "";
        String linea1 = strOf(direccion.get("direccion"));
        if (linea1.isBlank()) return "";
        String complemento = strOf(direccion.get("complemento"));
        String barrio = strOf(direccion.get("barrio"));
        String municipio = strOf(direccion.get("municipio"));
        String departamento = strOf(direccion.get("departamento"));

        StringBuilder sb = new StringBuilder(linea1);
        if (!complemento.isBlank()) sb.append(", ").append(complemento);
        if (!barrio.isBlank()) sb.append(" — ").append(barrio);
        if (!municipio.isBlank()) sb.append(", ").append(municipio);
        if (!departamento.isBlank()) sb.append(" (").append(departamento).append(")");
        return sb.toString();
    }

    private String strOf(Object o) {
        return o instanceof String s ? s : "";
    }

    private String formatPesos(long pesos) {
        return "$" + String.format("%,d", pesos).replace(',', '.');
    }

    /** Aviso al correo que el admin configuró en Ajustes — no usa override() a propósito:
     *  ese correo lo eligió el propio admin, no es un dato de un cliente de prueba. */
    public void sendNuevoPedido(Long tndId, String to, String numero, String clienteNombre, long totalPesos) {
        String totalFmt = String.format("$%,d", totalPesos).replace(',', '.');
        String html = """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:32px 24px;background:#fff">
              <h2 style="margin:0 0 8px;color:#111;font-size:20px">Nuevo pedido pagado</h2>
              <p style="color:#555;font-size:15px;margin:0 0 16px">
                <strong>%s</strong> hizo un pedido por <strong>%s</strong>.
              </p>
              <p style="color:#888;font-size:13px">Pedido #%s</p>
            </div>
            """.formatted(clienteNombre != null && !clienteNombre.isBlank() ? clienteNombre : "Un cliente",
                    totalFmt, numero);
        send(tndId, to, "Nuevo pedido — " + numero, html);
    }

    /** Pie común con la identidad de la tienda (§8.3) — cada línea se omite si el dato no está
     *  cargado, nunca se muestra un hueco vacío ni "null". */
    private String pie(TenantBrandingContext b) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin-top:28px;padding-top:16px;border-top:1px solid #eee;color:#999;font-size:12px;line-height:1.6\">");
        sb.append(escapeHtml(b.nombreComercial()));
        if (b.razonSocial() != null && !b.razonSocial().isBlank()) {
            sb.append(" — ").append(escapeHtml(b.razonSocial()));
        }
        if (b.nit() != null && !b.nit().isBlank()) {
            sb.append(" · NIT ").append(escapeHtml(b.nit()));
        }
        sb.append("<br>");
        boolean algo = false;
        if (b.emailSoporte() != null && !b.emailSoporte().isBlank()) {
            sb.append(escapeHtml(b.emailSoporte()));
            algo = true;
        }
        if (b.whatsapp() != null && !b.whatsapp().isBlank()) {
            if (algo) sb.append(" · ");
            sb.append("WhatsApp ").append(escapeHtml(b.whatsapp()));
            algo = true;
        }
        if (b.sitioWeb() != null && !b.sitioWeb().isBlank()) {
            if (algo) sb.append(" · ");
            sb.append(escapeHtml(b.sitioWeb()));
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String override(String original) {
        return (emailOverride != null && !emailOverride.isBlank()) ? emailOverride : original;
    }

    private void send(Long tndId, String to, String subject, String html) {
        try {
            ResendCredentials creds = integrationResolver.emailCredentials(tndId);
            RestClient.create()
                    .post()
                    .uri("https://api.resend.com/emails")
                    .header("Authorization", "Bearer " + creds.apiKey())
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "from", creds.from(),
                            "to", List.of(to),
                            "subject", subject,
                            "html", html
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[EMAIL] tenant={} enviado a={} asunto={}", tndId, to, subject);
        } catch (Exception e) {
            log.error("[EMAIL] tenant={} error enviando a={}: {}", tndId, to, e.getMessage());
            metrics.emailFallido(tndId);
        }
    }
}
