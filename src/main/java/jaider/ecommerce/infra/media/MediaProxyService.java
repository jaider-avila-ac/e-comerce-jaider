package jaider.ecommerce.infra.media;

import jaider.ecommerce.catalogo.producto.Producto;
import jaider.ecommerce.catalogo.producto.ProductoImagen;
import jaider.ecommerce.catalogo.producto.ProductoImagenRepository;
import jaider.ecommerce.catalogo.producto.ProductoRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Reenvía el contenido de imágenes alojadas en Cloudinary a través del propio dominio,
 * para que el navegador nunca vea una URL de un proveedor externo.
 */
@Service
@RequiredArgsConstructor
public class MediaProxyService {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private final ProductoImagenRepository imagenRepo;
    private final ProductoRepository productoRepo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public ProxiedImage fetchProductoImagen(Long tndId, Long imgId) {
        TenantContext.set(tndId.toString());
        try {
            tenantSupport.requireTenant(em);
            ProductoImagen img = imagenRepo.findById(imgId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen no encontrada"));

            // Hallazgo real (guía de medios, 2026-08-30): esto solo verificaba que la imagen
            // fuera de ESTE tenant (correcto vía RLS), pero nunca que el producto dueño siguiera
            // activo/visible — cualquiera que adivinara un pi_id consecutivo podía ver fotos de
            // productos ocultos/en borrador antes de que la tienda quisiera mostrarlos, algo que
            // PublicCatalogService.getProductoById() sí filtra (Producto::isActivo) pero este
            // proxy no. Mismo trato que "no encontrada" a propósito — no hay que confirmarle a
            // quien adivina el ID si el producto existe pero está oculto.
            Producto producto = productoRepo.findById(img.getPrdId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen no encontrada"));
            if (!producto.isActivo()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Imagen no encontrada");
            }

            return fetchRemote(img.getUrl());
        } finally {
            TenantContext.clear();
        }
    }

    private ProxiedImage fetchRemote(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo obtener la imagen");
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("image/jpeg");
            return new ProxiedImage(resp.body(), contentType);
        } catch (IOException | InterruptedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo obtener la imagen");
        }
    }

    public record ProxiedImage(byte[] bytes, String contentType) {}
}
