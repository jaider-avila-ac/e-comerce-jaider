package jaider.ecommerce.infra;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import jaider.ecommerce.tienda.TiendaRepository;
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

    private final Cloudinary cloudinary;
    private final TiendaRepository tiendaRepo;

    /**
     * @param productId  ID del producto (null cuando aún no se ha creado — se usa carpeta "new")
     * @param esVideo    true → resource_type video; false → image
     */
    public String upload(MultipartFile file, Long tndId, Long productId, boolean esVideo) throws IOException {
        // ecommerce/calzacaribe/productos/42/  ó  ecommerce/calzacaribe/productos/new/
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

    private String uploadToFolder(MultipartFile file, Long tndId, String subfolder, boolean esVideo) throws IOException {
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

        Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), params);

        String url = (String) result.get("secure_url");
        log.info("Archivo ({}) subido a Cloudinary en {}: {}", resourceType, folder, url);
        return url;
    }
}
