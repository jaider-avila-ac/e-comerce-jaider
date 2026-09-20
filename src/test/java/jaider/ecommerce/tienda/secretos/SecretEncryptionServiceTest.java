package jaider.ecommerce.tienda.secretos;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unitario puro (sin Spring, sin BD) — la llave de prueba se genera acá mismo, nunca es una real. */
class SecretEncryptionServiceTest {

    private static final String LLAVE_TEST = Base64.getEncoder().encodeToString(new byte[32]); // 32 ceros, solo para el test

    @Test
    void cifraYDescifra_devuelveElMismoTexto() {
        var service = new SecretEncryptionService(LLAVE_TEST);
        String original = "prv_test_super_secreta_123";

        String cifrado = service.encrypt(original);

        assertThat(cifrado).isNotEqualTo(original);
        assertThat(service.decrypt(cifrado)).isEqualTo(original);
    }

    @Test
    void mismoTextoDosVeces_daCifradosDistintos_porElIvAleatorio() {
        var service = new SecretEncryptionService(LLAVE_TEST);
        String original = "misma-credencial";

        String cifrado1 = service.encrypt(original);
        String cifrado2 = service.encrypt(original);

        assertThat(cifrado1).isNotEqualTo(cifrado2);
        assertThat(service.decrypt(cifrado1)).isEqualTo(original);
        assertThat(service.decrypt(cifrado2)).isEqualTo(original);
    }

    @Test
    void valorAlterado_fallaAlDescifrar_noDevuelveBasura() {
        var service = new SecretEncryptionService(LLAVE_TEST);
        String cifrado = service.encrypt("algo-secreto");

        // Cambia el último caracter del base64 — altera el ciphertext/tag de GCM.
        String alterado = cifrado.substring(0, cifrado.length() - 1)
                + (cifrado.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> service.decrypt(alterado)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sinLlaveConfigurada_fallaConMensajeClaro_noConUnaLlaveInventada() {
        var service = new SecretEncryptionService("");

        assertThatThrownBy(() -> service.encrypt("algo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECRETS_ENCRYPTION_KEY");
    }
}
