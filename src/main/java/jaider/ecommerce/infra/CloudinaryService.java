package jaider.ecommerce.infra;

import com.cloudinary.utils.ObjectUtils;
import jaider.ecommerce.shared.TenantCircuitBreaker;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.tienda.integracion.TenantCloudinaryClients;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private static final String PROVEEDOR = "cloudinary";

    private final TenantCloudinaryClients clientes;
    private final TiendaRepository tiendaRepo;
    private final TenantCircuitBreaker circuitBreaker;

    /**
     * @param productId  ID del producto (null cuando aún no se ha creado — se usa carpeta "new")
     * @param esVideo    true → resource_type video; false → image
     */
    public String upload(MultipartFile file, Long tndId, Long productId, boolean esVideo) throws IOException {
        // ecommerce/{slug}/productos/42/  ó  ecommerce/{slug}/productos/new/
        String productoFolder = productId != null ? String.valueOf(productId) : "new";
        return uploadToFolder(file, tndId, "productos/" + productoFolder, esVideo);
    }

    /**
     * @param esVideo true → resource_type video; false → image
     */
    public String uploadBanner(MultipartFile file, Long tndId, boolean esVideo) throws IOException {
        return uploadToFolder(file, tndId, "banners", esVideo);
    }

    public String uploadCategoria(MultipartFile file, Long tndId) throws IOException {
        return uploadToFolder(file, tndId, "categorias", false);
    }

    public String uploadColeccion(MultipartFile file, Long tndId) throws IOException {
        return uploadToFolder(file, tndId, "colecciones", false);
    }

    /** Foto de evidencia que el cliente adjunta a una solicitud de devolución. */
    public String uploadDevolucion(MultipartFile file, Long tndId, String numeroPedido) throws IOException {
        return uploadToFolder(file, tndId, "devoluciones/" + numeroPedido, false);
    }

    private String uploadToFolder(MultipartFile file, Long tndId, String subfolder, boolean esVideo) throws IOException {
        if (circuitBreaker.abierto(tndId, PROVEEDOR)) {
            // Ya se sabe que Cloudinary está fallando para esta tienda — mismo IOException que
            // cualquier otro fallo de subida, para que el llamador (UploadController) lo trate
            // exactamente igual (§14: no esperar otro timeout inútil).
            throw new IOException("Cloudinary no disponible temporalmente para esta tienda");
        }

        String slug = tiendaRepo.findById(tndId)
                .map(t -> t.getSlug())
                .orElse("default");

        String folder = "ecommerce/" + slug + "/" + subfolder;
        String resourceType = esVideo ? "video" : "image";

        var params = esVideo
                ? ObjectUtils.asMap(
                        "folder",        folder,
                        "resource_type", "video",
                        "quality",       "auto",
                        "video_codec",   "auto")
                : ObjectUtils.asMap(
                        "folder",        folder,
                        "resource_type", "image",
                        "quality",       "auto",
                        "fetch_format",  "auto");

        Map<?, ?> result;
        try {
            result = clientes.get(tndId).uploader().upload(file.getBytes(), params);
        } catch (IOException e) {
            circuitBreaker.registrarFallo(tndId, PROVEEDOR);
            throw e;
        }
        circuitBreaker.registrarExito(tndId, PROVEEDOR);

        String url = (String) result.get("secure_url");
        log.info("Archivo ({}) subido a Cloudinary en {}: {}", resourceType, folder, url);
        return url;
    }

    /**
     * Borra un archivo de Cloudinary a partir de su secure_url (nunca lanza — un fallo acá
     * no debe impedir que la operación real en la base de datos se complete). Se usa cuando
     * una imagen/video se reemplaza o se borra, y al borrar productos/banners/categorías, para
     * que nunca queden archivos huérfanos en el gestor de imágenes.
     *
     * @param tndId tienda dueña del archivo — determina qué cuenta Cloudinary usar. Nunca se
     *              debe borrar con la cuenta de un tenant un recurso subido por otro.
     */
    public void delete(String url, Long tndId) {
        if (url == null || url.isBlank()) return;
        if (circuitBreaker.abierto(tndId, PROVEEDOR)) {
            log.warn("No se intenta eliminar de Cloudinary (circuito abierto para tenant={}): {}", tndId, url);
            return;
        }
        try {
            String resourceType = url.contains("/video/upload/") ? "video" : "image";
            String publicId = extractPublicId(url);
            if (publicId == null) return;
            clientes.get(tndId).uploader().destroy(publicId, ObjectUtils.asMap("resource_type", resourceType));
            log.info("Archivo eliminado de Cloudinary: {}", publicId);
            circuitBreaker.registrarExito(tndId, PROVEEDOR);
        } catch (Exception e) {
            log.warn("No se pudo eliminar de Cloudinary la url {}: {}", url, e.getMessage());
            circuitBreaker.registrarFallo(tndId, PROVEEDOR);
        }
    }

    /** Borra una carpeta de Cloudinary (debe estar vacía) — best-effort, ej. tras borrar el
     *  último archivo de la carpeta dedicada de un producto. Nunca lanza. */
    public void deleteFolder(String folder, Long tndId) {
        if (folder == null || folder.isBlank()) return;
        if (circuitBreaker.abierto(tndId, PROVEEDOR)) {
            log.warn("No se intenta borrar carpeta de Cloudinary (circuito abierto para tenant={}): {}", tndId, folder);
            return;
        }
        try {
            clientes.get(tndId).api().deleteFolder(folder, ObjectUtils.emptyMap());
            log.info("Carpeta eliminada de Cloudinary: {}", folder);
            circuitBreaker.registrarExito(tndId, PROVEEDOR);
        } catch (Exception e) {
            log.warn("No se pudo eliminar la carpeta de Cloudinary {}: {}", folder, e.getMessage());
            circuitBreaker.registrarFallo(tndId, PROVEEDOR);
        }
    }

    /** Carpeta dedicada de un producto (ver upload()), para limpiarla cuando se borra el producto. */
    public String folderDeProducto(Long tndId, Long productId) {
        String slug = tiendaRepo.findById(tndId).map(t -> t.getSlug()).orElse("default");
        return "ecommerce/" + slug + "/productos/" + productId;
    }

    /**
     * Extrae el public_id (incluye la carpeta) de una secure_url de Cloudinary, ej.:
     * https://res.cloudinary.com/demo/image/upload/v1699999999/ecommerce/tienda/productos/42/abc123.jpg
     * -> ecommerce/tienda/productos/42/abc123
     */
    private String extractPublicId(String url) {
        int uploadIdx = url.indexOf("/upload/");
        if (uploadIdx < 0) return null;
        String afterUpload = url.substring(uploadIdx + "/upload/".length());
        afterUpload = afterUpload.replaceFirst("^v\\d+/", "");
        int lastDot = afterUpload.lastIndexOf('.');
        return lastDot > 0 ? afterUpload.substring(0, lastDot) : afterUpload;
    }
}
