package jaider.ecommerce.tienda.integracion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración REAL (sin mocks, `.env` local de verdad) de la resolución de credenciales por
 * tenant — cierra el punto 5 pendiente de multitenant_plan.md. A pedido explícito del usuario
 * (2026-08-30): usa las llaves YA configuradas (todas de Calzacaribe hoy — Wompi es su cuenta
 * real/permanente, Resend y Cloudinary son prestadas y se reemplazarán más adelante).
 *
 * NO llama a ningún proveedor externo por red — solo prueba que TenantIntegrationResolver arma
 * y aísla las credenciales correctamente. Por diseño (ver TenantIntegrationResolver), cambiar
 * Resend/Cloudinary más adelante NO requiere tocar este archivo: solo se reemplazan las
 * variables de entorno `RESEND_CALZADO_CARIBE_*`/`CLOUDINARY_CALZADO_CARIBE_*`, este test sigue
 * pasando igual porque nunca compara contra un valor esperado hardcodeado, solo confirma
 * presencia/ausencia y aislamiento entre tenants.
 *
 * Seguridad (la otra mitad de lo pedido): estas aserciones NUNCA comparan el valor real de una
 * llave contra un literal ni entre sí con `isEqualTo` — eso haría que AssertJ imprimiera la
 * llave completa en el mensaje de un test fallido, quedando en la consola/CI. Solo se afirma
 * "no está en blanco" (si falla, el valor real SIEMPRE es null/vacío, nunca un secreto) o
 * "lanzó esta excepción" (el mensaje de {@link TenantIntegrationResolver} solo nombra la
 * variable de entorno que falta, nunca su valor).
 */
@SpringBootTest
class TenantIntegrationResolverSecurityTest {

    @Autowired
    private TenantIntegrationResolver resolver;

    // Tenant 1 = Calzacaribe (alias CALZADO_CARIBE) — credenciales reales configuradas hoy.
    // Tenant 2 = "Tienda Test B" (alias TIENDA_TEST_B) — sin NINGUNA credencial, a propósito,
    // desde que se creó en Fase 0 — es el tenant "negativo" de estas pruebas.

    @Test
    void wompi_tenant1ResuelveCredencialesReales_tenant2FallaLimpioSinCaerATenant1() {
        WompiCredentials creds = resolver.paymentCredentials(1L);
        assertThat(creds.publicKey()).isNotBlank();
        assertThat(creds.integrityKey()).isNotBlank();
        assertThat(creds.eventsKey()).isNotBlank();

        assertThatThrownBy(() -> resolver.paymentCredentials(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WOMPI_TIENDA_TEST_B_PUBLIC_KEY");
    }

    @Test
    void resend_tenant1ResuelveCredencialesReales_tenant2FallaLimpioSinCaerATenant1() {
        ResendCredentials creds = resolver.emailCredentials(1L);
        assertThat(creds.apiKey()).isNotBlank();
        assertThat(creds.from()).isNotBlank();

        assertThatThrownBy(() -> resolver.emailCredentials(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_TIENDA_TEST_B_API_KEY");
    }

    @Test
    void cloudinary_tenant1ResuelveCredencialesReales_tenant2FallaLimpioSinCaerATenant1() {
        CloudinaryCredentials creds = resolver.mediaCredentials(1L);
        assertThat(creds.cloudName()).isNotBlank();
        assertThat(creds.apiKey()).isNotBlank();
        assertThat(creds.apiSecret()).isNotBlank();

        assertThatThrownBy(() -> resolver.mediaCredentials(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOUDINARY_TIENDA_TEST_B_CLOUD_NAME");
    }

    @Test
    void envia_niCalzacaribeNiTenant2TienenCredencialesConfiguradas_fallanLimpioLosDos() {
        // A diferencia de Wompi/Resend/Cloudinary, Calzacaribe NO usa Envia (PLAN_INTEGRACION_
        // ENVIA.md: envío calculado es opcional, solo para tiendas nuevas que lo activen) — hoy
        // ningún tenant local tiene ENVIA_*_API_TOKEN configurado, así que ambos deben fallar
        // limpio, nunca con un 500 opaco ni con un valor inventado.
        assertThatThrownBy(() -> resolver.envioCredentials(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENVIA_CALZADO_CARIBE_API_TOKEN");
        assertThatThrownBy(() -> resolver.envioCredentials(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENVIA_TIENDA_TEST_B_API_TOKEN");
    }

    @Test
    void tenant2SinTiendaEnBd_lanzaAntesDeSiquieraMirarVariablesDeEntorno() {
        assertThatThrownBy(() -> resolver.mediaCredentials(999_999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no existe");
    }

    @Test
    void losRecordsDeCredencialesNuncaImprimenSuContenidoEnToString() {
        // Defensa en profundidad (§6.3 del plan: "nunca registrar los valores"): si en algún
        // futuro alguien loguea el objeto entero por error (log.debug("{}", creds)), el toString()
        // redactado evita que la llave real termine en un log — ver el override en cada record.
        // publicKey NO es secreta a propósito (Wompi la expone en el checkout del navegador) —
        // el toString() redactado la deja visible; solo las llaves realmente secretas deben
        // faltar. privateKey es la única opcional de las 4 (ver TenantIntegrationResolver) —
        // si viniera en blanco, doesNotContain("") sería siempre verdadero por definición
        // (toda cadena "contiene" ""), así que se omite de la lista en ese caso.
        WompiCredentials wompi = resolver.paymentCredentials(1L);
        assertThat(wompi.toString()).doesNotContain(wompi.integrityKey(), wompi.eventsKey());
        if (!wompi.privateKey().isBlank()) {
            assertThat(wompi.toString()).doesNotContain(wompi.privateKey());
        }

        ResendCredentials resend = resolver.emailCredentials(1L);
        assertThat(resend.toString()).doesNotContain(resend.apiKey());

        CloudinaryCredentials cloudinary = resolver.mediaCredentials(1L);
        assertThat(cloudinary.toString()).doesNotContain(cloudinary.apiKey(), cloudinary.apiSecret());
    }
}
