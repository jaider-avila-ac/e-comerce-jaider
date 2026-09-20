package jaider.ecommerce.tienda.secretos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra/descifra las credenciales de integración de una tienda antes de guardarlas en
 * `tienda_secretos` — decisión explícita del usuario (2026-08-31): estas SÍ viven en la BD (a
 * diferencia de las de tiendas configuradas por variable de entorno), pero nunca en texto plano.
 *
 * AES-256-GCM (cifrado autenticado: si alguien altera el valor cifrado, descifrar falla en vez
 * de devolver basura silenciosamente). Cada valor lleva su propio IV aleatorio de 12 bytes
 * (estándar para GCM), guardado junto al texto cifrado — nunca se reutiliza un IV con la misma
 * llave. Formato guardado: base64(IV[12] + ciphertext+tag).
 *
 * La llave maestra vive SOLO en la variable de entorno {@code SECRETS_ENCRYPTION_KEY} (256 bits,
 * base64) — nunca en código, nunca en la BD, nunca en un log. Sin ella, cifrar/descifrar falla
 * con un mensaje claro (nunca se genera ni se asume una llave por defecto — eso sería
 * indistinguible de no tener cifrado real).
 */
@Component
public class SecretEncryptionService {

    private static final String TRANSFORMACION = "AES/GCM/NoPadding";
    private static final int TAM_IV = 12;
    private static final int TAM_TAG_BITS = 128;

    private final String base64Key;

    public SecretEncryptionService(@Value("${secrets.encryption-key:}") String base64Key) {
        this.base64Key = base64Key;
    }

    public String encrypt(String textoPlano) {
        try {
            byte[] iv = new byte[TAM_IV];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.ENCRYPT_MODE, llave(), new GCMParameterSpec(TAM_TAG_BITS, iv));
            byte[] cifrado = cipher.doFinal(textoPlano.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cifrado.length);
            buffer.put(iv).put(cifrado);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar la credencial", e);
        }
    }

    public String decrypt(String valorCifrado) {
        try {
            byte[] data = Base64.getDecoder().decode(valorCifrado);
            byte[] iv = new byte[TAM_IV];
            byte[] cifrado = new byte[data.length - TAM_IV];
            System.arraycopy(data, 0, iv, 0, TAM_IV);
            System.arraycopy(data, TAM_IV, cifrado, 0, cifrado.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.DECRYPT_MODE, llave(), new GCMParameterSpec(TAM_TAG_BITS, iv));
            byte[] textoPlano = cipher.doFinal(cifrado);
            return new String(textoPlano, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            // IllegalArgumentException: base64 corrupto (ni siquiera llegó a intentar descifrar).
            // GeneralSecurityException: el tag de autenticación de GCM no valida (dato alterado o
            // llave incorrecta). Ambos casos deben verse igual desde afuera — nunca envolver acá
            // el valor cifrado ni la llave en el mensaje de error, solo importa QUE falló.
            throw new IllegalStateException("No se pudo descifrar la credencial (¿llave incorrecta o dato alterado?)", e);
        }
    }

    private SecretKeySpec llave() {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "Falta la variable de entorno SECRETS_ENCRYPTION_KEY — no se puede cifrar/descifrar ninguna credencial sin ella");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "SECRETS_ENCRYPTION_KEY debe decodificar a 32 bytes (AES-256) — tiene " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }
}
