package jaider.ecommerce.infra;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * extractPublicId es parseo puro (sin red) — no hace falta contexto de Spring ni mockear el
 * cliente de Cloudinary, por eso se instancia directo con los colaboradores en null.
 *
 * Cubre el caso que rompió al agregar f_auto,q_auto a la URL de las imágenes (ver
 * CloudinaryService.uploadToFolder): el segmento de transformación queda ANTES de la versión, y
 * si extractPublicId no lo salta, delete() intenta borrar un public_id equivocado (con
 * "f_auto,q_auto/" pegado al inicio) — Cloudinary lo rechaza y el archivo queda huérfano.
 */
class CloudinaryServiceTest {

    private final CloudinaryService service = new CloudinaryService(null, null, null);

    @Test
    void urlConTransformacionFAutoQAuto_extraePublicIdCorrecto() {
        String url = "https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v1699999999/"
                + "ecommerce/tienda/productos/42/abc123.jpg";
        assertThat(service.extractPublicId(url)).isEqualTo("ecommerce/tienda/productos/42/abc123");
    }

    @Test
    void urlSinTransformacion_extraePublicIdCorrecto() {
        // Formato de antes de agregar f_auto,q_auto — las imágenes ya subidas siguen así.
        String url = "https://res.cloudinary.com/demo/image/upload/v1699999999/"
                + "ecommerce/tienda/productos/42/abc123.jpg";
        assertThat(service.extractPublicId(url)).isEqualTo("ecommerce/tienda/productos/42/abc123");
    }

    @Test
    void urlDeVideo_sinTransformacion_extraePublicIdCorrecto() {
        // Los videos no llevan f_auto,q_auto (ver uploadToFolder: solo aplica a esVideo=false).
        String url = "https://res.cloudinary.com/demo/video/upload/v1699999999/"
                + "ecommerce/tienda/productos/42/clip.mp4";
        assertThat(service.extractPublicId(url)).isEqualTo("ecommerce/tienda/productos/42/clip");
    }

    @Test
    void urlSinExtensionDeArchivo_devuelveTalCual() {
        String url = "https://res.cloudinary.com/demo/image/upload/v1699999999/"
                + "ecommerce/tienda/productos/42/abc123";
        assertThat(service.extractPublicId(url)).isEqualTo("ecommerce/tienda/productos/42/abc123");
    }

    @Test
    void urlSinUpload_devuelveNull() {
        assertThat(service.extractPublicId("https://res.cloudinary.com/demo/image/foo")).isNull();
    }

    @Test
    void imagenMasGrandeQueElLadoMaximo_seReduceYPesaMenos() throws IOException {
        byte[] original = jpegDeColorSolido(3000, 2000, Color.BLUE);

        byte[] comprimida = service.comprimir(original);

        BufferedImage resultado = ImageIO.read(new ByteArrayInputStream(comprimida));
        // Original 3000x2000 (lado mayor 3000) → se reduce a que el lado mayor quede en 2000,
        // manteniendo la proporción 3:2 → ancho 2000, alto 1333.
        assertThat(resultado.getWidth()).isEqualTo(2000);
        assertThat(resultado.getHeight()).isEqualTo(1333);
        assertThat(comprimida.length).isLessThan(original.length);
    }

    @Test
    void imagenDentroDelLadoMaximo_noSeRedimensiona() throws IOException {
        byte[] original = jpegDeColorSolido(800, 600, Color.RED);

        byte[] comprimida = service.comprimir(original);

        BufferedImage resultado = ImageIO.read(new ByteArrayInputStream(comprimida));
        assertThat(resultado.getWidth()).isEqualTo(800);
        assertThat(resultado.getHeight()).isEqualTo(600);
    }

    @Test
    void bytesQueNoSonUnaImagen_seDevuelvenTalCual() {
        byte[] basura = "esto no es una imagen".getBytes();
        assertThat(service.comprimir(basura)).isEqualTo(basura);
    }

    private byte[] jpegDeColorSolido(int ancho, int alto, Color color) throws IOException {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, ancho, alto);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
