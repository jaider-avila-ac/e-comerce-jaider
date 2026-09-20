package jaider.ecommerce.tienda.integracion;

import com.cloudinary.utils.ObjectUtils;
import jaider.ecommerce.pago.wompi.WompiGatewayFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Prueba en vivo, sin cobrar/enviar/subir nada real, que las integraciones de una tienda
 * (Cloudinary/Resend/Wompi/Envia) están correctamente configuradas — PLAN_MEJORAS_API_ECOMMERCE_
 * MULTITENANT.md §14 ("health checks de integraciones por tenant") y §15 (pasos 5-8 del
 * aprovisionamiento de una tienda nueva, que reutiliza este mismo servicio).
 *
 * Nunca lanza — cada chequeo atrapa su propio error y lo reporta como {@link IntegracionSalud};
 * una integración mal configurada no debe romper la consulta de las demás.
 */
@Component
@RequiredArgsConstructor
public class TenantIntegrationHealthService {

    private final TenantIntegrationResolver integrationResolver;
    private final TenantCloudinaryClients cloudinaryClients;
    private final WompiGatewayFactory wompiGatewayFactory;

    public List<IntegracionSalud> chequear(Long tndId) {
        return List.of(chequearCloudinary(tndId), chequearResend(tndId), chequearWompi(tndId), chequearEnvia(tndId));
    }

    private IntegracionSalud chequearCloudinary(Long tndId) {
        try {
            cloudinaryClients.get(tndId).api().ping(ObjectUtils.emptyMap());
            return new IntegracionSalud("cloudinary", true, "OK");
        } catch (Exception e) {
            return new IntegracionSalud("cloudinary", false, mensajeSeguro(e));
        }
    }

    /** GET /api-keys solo confirma que la llave autentica — no envía ningún correo. */
    private IntegracionSalud chequearResend(Long tndId) {
        try {
            ResendCredentials creds = integrationResolver.emailCredentials(tndId);
            RestClient.create()
                    .get()
                    .uri("https://api.resend.com/api-keys")
                    .header("Authorization", "Bearer " + creds.apiKey())
                    .retrieve()
                    .toBodilessEntity();
            return new IntegracionSalud("resend", true, "OK");
        } catch (Exception e) {
            return new IntegracionSalud("resend", false, mensajeSeguro(e));
        }
    }

    /** obtenerTokensAceptacion() ya es una llamada real pero inofensiva (GET público del
     *  merchant, sin autenticación ni cobro) — la misma que usa el checkout normal. */
    private IntegracionSalud chequearWompi(Long tndId) {
        try {
            wompiGatewayFactory.forTenant(tndId).obtenerTokensAceptacion();
            return new IntegracionSalud("wompi", true, "OK");
        } catch (Exception e) {
            return new IntegracionSalud("wompi", false, mensajeSeguro(e));
        }
    }

    /** GET /carrier de la API de consultas de Envia — solo lista transportadoras disponibles
     *  para Colombia, de solo lectura, no crea ninguna guía ni genera ningún cargo. Fuente:
     *  docs.envia.com/reference/carriers-by-country.
     *
     *  OJO (verificado en vivo, 2026-08-31): la documentación sugiere un host separado de
     *  sandbox para esta API ("queries-test.envia.com"), pero probando contra el real ese host
     *  responde 404 "No such app" — no existe. Solo queries.envia.com es real (confirmado:
     *  responde 401 "Missing authentication" con www-authenticate: Bearer sin token). Esta API
     *  de consultas es de referencia pura (lista de transportadoras/países), no crea nada, así
     *  que no necesita distinguir sandbox/producción por host — el token sandbox/producción de
     *  Tienda.getEnviaAmbiente() sigue siendo el que de verdad separa los dos ambientes en el
     *  resto de la API de Envia (cotizar/generar guía, Fase 3), no este chequeo. */
    private IntegracionSalud chequearEnvia(Long tndId) {
        try {
            EnviaCredentials creds = integrationResolver.envioCredentials(tndId);
            RestClient.create()
                    .get()
                    .uri("https://queries.envia.com/carrier?country_code=CO")
                    .header("Authorization", "Bearer " + creds.apiToken())
                    .retrieve()
                    .toBodilessEntity();
            return new IntegracionSalud("envia", true, "OK");
        } catch (Exception e) {
            return new IntegracionSalud("envia", false, mensajeSeguro(e));
        }
    }

    /** Nunca debe filtrar una llave real en el mensaje (§14: nunca loguear secretos) — los
     *  mensajes de los clientes HTTP normalmente no incluyen el header Authorization enviado,
     *  pero se sanitiza igual por si acaso. */
    private String mensajeSeguro(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        return msg.replaceAll("(?i)(bearer|basic)\\s+\\S+", "$1 ***");
    }
}
