package jaider.ecommerce.catalogo.producto;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.catalogo.CatalogCacheService;
import jaider.ecommerce.infra.CloudinaryService;
import jaider.ecommerce.notificacion.event.StockDisponibleEvent;
import jaider.ecommerce.shared.dto.PageResponse;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductoService {

    private final ProductoRepository productoRepo;
    private final VarianteRepository varianteRepo;
    private final ProductoImagenRepository imagenRepo;
    private final TenantSupport tenantSupport;
    private final CatalogCacheService catalogCache;
    private final ApplicationEventPublisher eventPublisher;
    private final CloudinaryService cloudinaryService;

    @PersistenceContext
    private EntityManager em;

    // ─── Queries ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<ProductoResponse> search(Long catId, Boolean activo, String q, int page, int size) {
        tenantSupport.applyTenant(em);
        String tenantId = TenantContext.get();
        String qNorm = (q == null || q.isBlank()) ? null : q.trim();

        long version = catalogCache.currentVersion(tenantId);
        String cacheKey = catalogCache.key(tenantId, version, "admin-productos",
                String.valueOf(catId), String.valueOf(activo), qNorm == null ? "-" : qNorm,
                "p" + page, "s" + size);

        return catalogCache.getOrLoad(cacheKey, Duration.ofMinutes(2), () -> {
            Pageable pageable = PageRequest.of(page, size);
            Page<Producto> result = productoRepo.search(catId, activo, qNorm, pageable);
            List<ProductoResponse> content = result.getContent().stream().map(this::toResponse).toList();
            return new PageResponse<>(content, result.getNumber(), result.getSize(),
                    result.getTotalElements(), result.getTotalPages());
        }, new TypeReference<PageResponse<ProductoResponse>>() {});
    }

    @Transactional(readOnly = true)
    public ProductoResponse getById(Long id) {
        tenantSupport.applyTenant(em);
        Producto p = productoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
        return toResponse(p);
    }

    // ─── Mutations ────────────────────────────────────────────────────────────

    @Transactional
    public ProductoResponse create(ProductoRequest req) {
        tenantSupport.applyTenant(em);
        String tndId = TenantContext.get();

        Producto p = new Producto();
        p.setTndId(Long.parseLong(tndId));
        applyRequest(p, req);
        p = productoRepo.save(p);

        saveVariantes(p.getId(), req.variantes());
        saveImagenes(p.getId(), req.imagenes());

        catalogCache.invalidate(TenantContext.get());
        return toResponse(p);
    }

    @Transactional
    public ProductoResponse update(Long id, ProductoRequest req) {
        tenantSupport.applyTenant(em);

        Producto p = productoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
        applyRequest(p, req);
        p = productoRepo.save(p);

        if (req.variantes() != null) {
            updateVariantes(p.getId(), req.variantes());
        }
        if (req.imagenes() != null) {
            List<String> urlsAnteriores = imagenRepo.findByPrdIdOrderByOrdenAscIdAsc(p.getId())
                    .stream().map(ProductoImagen::getUrl).toList();
            imagenRepo.deleteByPrdId(p.getId());
            saveImagenes(p.getId(), req.imagenes());

            // Solo se borran de Cloudinary las que ya no están en la lista nueva — las que el
            // admin conservó se re-insertan con nueva fila pero es la misma imagen remota.
            Set<String> urlsNuevas = req.imagenes().stream().map(ImagenRequest::url).collect(java.util.stream.Collectors.toSet());
            urlsAnteriores.stream().filter(u -> !urlsNuevas.contains(u)).forEach(cloudinaryService::delete);
        }

        catalogCache.invalidate(TenantContext.get());
        return toResponse(p);
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.applyTenant(em);
        Producto p = productoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));

        List<String> urls = imagenRepo.findByPrdIdOrderByOrdenAscIdAsc(id)
                .stream().map(ProductoImagen::getUrl).toList();

        imagenRepo.deleteByPrdId(id);
        varianteRepo.deleteByPrdId(id);
        productoRepo.deleteById(id);
        catalogCache.invalidate(TenantContext.get());

        // Nunca deben quedar archivos ni carpetas huérfanas en Cloudinary tras borrar un producto.
        urls.forEach(cloudinaryService::delete);
        cloudinaryService.deleteFolder(cloudinaryService.folderDeProducto(p.getTndId(), id));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> inventarioResumen() {
        tenantSupport.applyTenant(em);
        Object[] row = (Object[]) em.createNativeQuery("""
            SELECT
              COALESCE(SUM(var_stock), 0)                                             AS total_stock,
              COUNT(*) FILTER (WHERE var_stock = 0)                                   AS agotadas,
              COUNT(*) FILTER (WHERE var_stock > 0 AND var_stock <= 5)                AS bajo_stock,
              COUNT(*) FILTER (WHERE var_stock > 5)                                   AS disponibles
            FROM variantes
            WHERE var_activo = true
            """).getSingleResult();
        return Map.of(
                "total_stock",  ((Number) row[0]).longValue(),
                "agotadas",     ((Number) row[1]).longValue(),
                "bajo_stock",   ((Number) row[2]).longValue(),
                "disponibles",  ((Number) row[3]).longValue()
        );
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> inventario() {
        tenantSupport.applyTenant(em);
        List<Object[]> rows = em.createNativeQuery("""
            SELECT v.var_id,
                   v.var_prd_id,
                   p.prd_nombre,
                   v.var_talla,
                   v.var_color,
                   v.var_color_hex,
                   v.var_stock,
                   v.var_activo,
                   CASE WHEN v.var_stock = 0 THEN 'agotado'
                        WHEN v.var_stock <= 5 THEN 'bajo'
                        ELSE 'disponible' END AS estado_stock,
                   p.prd_ficha_tecnica ->> 'marca' AS marca,
                   SUM(v.var_stock) OVER (PARTITION BY v.var_prd_id) AS total_stock_producto,
                   CASE MIN(CASE WHEN v.var_stock = 0 THEN 0 WHEN v.var_stock <= 5 THEN 1 ELSE 2 END)
                        OVER (PARTITION BY v.var_prd_id)
                        WHEN 0 THEN 'agotado' WHEN 1 THEN 'bajo' ELSE 'disponible' END AS estado_producto
            FROM variantes v
            JOIN productos p ON p.prd_id = v.var_prd_id
            WHERE v.var_activo = true
            ORDER BY p.prd_nombre ASC, v.var_talla ASC, v.var_color ASC, v.var_id ASC
            """).getResultList();

        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("var_id",       ((Number) r[0]).longValue());
            item.put("prd_id",       ((Number) r[1]).longValue());
            item.put("nombre",       r[2] != null ? r[2] : "");
            item.put("talla",        r[3] != null ? r[3] : "");
            item.put("color",        r[4] != null ? r[4] : "");
            item.put("color_hex",    r[5] != null ? r[5] : "#000000");
            item.put("stock",        ((Number) r[6]).intValue());
            item.put("activo",       r[7]);
            item.put("estado_stock", r[8] != null ? r[8] : "disponible");
            item.put("marca",        r[9]);
            item.put("total_stock_producto", ((Number) r[10]).longValue());
            item.put("estado_producto", r[11]);
            items.add(item);
        }

        return Map.of(
                "items", items,
                "resumen", inventarioResumen()
        );
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getLowStock(int limite) {
        tenantSupport.applyTenant(em);
        List<Object[]> rows = em.createNativeQuery("""
            SELECT v.var_id, p.prd_nombre, v.var_talla, v.var_color, v.var_stock,
                   CASE WHEN v.var_stock = 0 THEN 'agotado'
                        WHEN v.var_stock <= 5 THEN 'bajo'
                        ELSE 'disponible' END AS estado_stock
            FROM variantes v
            JOIN productos p ON p.prd_id = v.var_prd_id
            WHERE v.var_stock <= 5 AND v.var_activo = true
            ORDER BY v.var_stock ASC, p.prd_nombre ASC
            LIMIT :limite
            """)
            .setParameter("limite", limite)
            .getResultList();

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            result.add(Map.of(
                    "id",              ((Number) r[0]).longValue(),
                    "producto_nombre", r[1],
                    "talla",           r[2] != null ? r[2] : "",
                    "color",           r[3] != null ? r[3] : "",
                    "stock",           ((Number) r[4]).intValue(),
                    "estado_stock",    r[5] != null ? r[5] : "disponible"
            ));
        }
        return result;
    }

    @Transactional
    public VarianteResponse updateStock(Long varId, Integer cantidad) {
        tenantSupport.applyTenant(em);
        Variante v = varianteRepo.findById(varId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Variante no encontrada"));

        boolean estabaAgotado = estabaAgotado(v);
        v.setStock(cantidad);
        catalogCache.invalidate(TenantContext.get());
        VarianteResponse response = toVarianteResponse(varianteRepo.save(v));

        if (estabaAgotado && cantidad != null && cantidad > 0) {
            productoRepo.findById(v.getPrdId()).ifPresent(p ->
                    eventPublisher.publishEvent(new StockDisponibleEvent(p.getTndId(), p.getId(), p.getNombre())));
        }

        return response;
    }

    /** true si, antes de este cambio, el producto entero (todas sus variantes) estaba sin stock. */
    private boolean estabaAgotado(Variante v) {
        Integer stockActual = v.getStock();
        if (stockActual != null && stockActual > 0) return false;

        Number otrasConStock = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM variantes WHERE var_prd_id = :prdId AND var_id <> :varId AND var_stock > 0")
                .setParameter("prdId", v.getPrdId())
                .setParameter("varId", v.getId())
                .getSingleResult();
        return otrasConStock.longValue() == 0;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void applyRequest(Producto p, ProductoRequest req) {
        if (req.catId() != null) p.setCatId(req.catId());
        p.setSubId(req.subId());
        if (req.nombre() != null) p.setNombre(req.nombre());
        if (req.slug() != null) p.setSlug(req.slug());
        if (req.descripcion() != null) p.setDescripcion(req.descripcion());
        if (req.precioAntes() != null) {
            if (req.precio() != null && req.precio() >= req.precioAntes()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El precio de oferta debe ser menor al precio normal");
            }
            p.setPrecioCentavos(req.precioAntes() * 100L);
            p.setPrecioDescuentoCentavos(req.precio() * 100L);
        } else if (req.precio() != null) {
            p.setPrecioCentavos(req.precio() * 100L);
            p.setPrecioDescuentoCentavos(null);
        }
        // ofertaHasta: solo relevante cuando hay precio con descuento
        if (req.precioAntes() != null) {
            p.setOfertaHasta(req.ofertaHasta());
        } else {
            p.setOfertaHasta(null);
        }
        if (req.fichaTecnica() != null) p.setFichaTecnica(req.fichaTecnica());
        if (req.activo() != null) p.setActivo(req.activo());
    }

    private void saveVariantes(Long prdId, List<VarianteRequest> list) {
        if (list == null || list.isEmpty()) return;
        for (VarianteRequest vr : list) {
            Variante v = new Variante();
            v.setPrdId(prdId);
            applyVarianteRequest(v, vr);
            varianteRepo.save(v);
        }
    }

    private void updateVariantes(Long prdId, List<VarianteRequest> list) {
        List<Variante> existentes = varianteRepo.findByPrdIdOrderByIdAsc(prdId);
        Set<Long> usadas = new HashSet<>();

        if (list != null) {
            for (VarianteRequest vr : list) {
                Variante v = findVarianteForRequest(existentes, usadas, prdId, vr);
                applyVarianteRequest(v, vr);
                varianteRepo.save(v);
                if (v.getId() != null) usadas.add(v.getId());
            }
        }

        for (Variante v : existentes) {
            if (v.getId() == null || usadas.contains(v.getId())) continue;
            if (hasInventoryMovements(v.getId())) {
                v.setActivo(false);
                varianteRepo.save(v);
            } else {
                varianteRepo.delete(v);
            }
        }
    }

    private Variante findVarianteForRequest(List<Variante> existentes, Set<Long> usadas, Long prdId, VarianteRequest vr) {
        if (vr.id() != null) {
            for (Variante v : existentes) {
                if (Objects.equals(v.getId(), vr.id()) && Objects.equals(v.getPrdId(), prdId)) {
                    return v;
                }
            }
        }

        for (Variante v : existentes) {
            if (v.getId() != null && usadas.contains(v.getId())) continue;
            if (sameVariant(v, vr)) return v;
        }

        Variante nueva = new Variante();
        nueva.setPrdId(prdId);
        return nueva;
    }

    private void applyVarianteRequest(Variante v, VarianteRequest vr) {
        v.setTalla(vr.talla());
        v.setColor(vr.color());
        v.setColorHex(vr.colorHex());
        v.setStock(vr.stock() != null ? vr.stock() : 0);
        v.setActivo(vr.activo() == null || vr.activo());
    }

    private boolean sameVariant(Variante v, VarianteRequest vr) {
        return Objects.equals(clean(v.getTalla()), clean(vr.talla()))
                && Objects.equals(clean(v.getColor()), clean(vr.color()))
                && Objects.equals(clean(v.getColorHex()), clean(vr.colorHex()));
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private boolean hasInventoryMovements(Long varId) {
        Number count = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM movimientos_inventario WHERE mov_var_id = :varId")
                .setParameter("varId", varId)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private void saveImagenes(Long prdId, List<ImagenRequest> list) {
        if (list == null || list.isEmpty()) return;
        short orden = 0;
        for (ImagenRequest ir : list) {
            String tipo = (ir.tipo() != null && !ir.tipo().isBlank()) ? ir.tipo() : "imagen";
            Long varId = resolverVarIdPorColor(prdId, ir.color());
            em.createNativeQuery(
                    "INSERT INTO producto_imagenes (pi_prd_id, pi_var_id, pi_url, pi_tipo, pi_orden) " +
                    "VALUES (:prdId, :varId, :url, CAST(:tipo AS tipo_media), :orden)"
            )
            .setParameter("prdId", prdId)
            .setParameter("varId", varId)
            .setParameter("url",   ir.url())
            .setParameter("tipo",  tipo)
            .setParameter("orden", ir.orden() != null ? ir.orden() : orden++)
            .executeUpdate();
        }
    }

    /** Resuelve el color elegido en el frontend a un var_id real. Nunca se recibe el var_id
     *  directo del cliente: al crear un producto nuevo, las variantes recién elegidas todavía no
     *  tienen id en el momento en que el admin arma la lista de imágenes en el navegador — esta
     *  función corre después de que saveVariantes()/updateVariantes() ya insertó las variantes,
     *  así que el color siempre es resoluble a esa altura. */
    private Long resolverVarIdPorColor(Long prdId, String color) {
        if (color == null || color.isBlank()) return null;
        try {
            return ((Number) em.createNativeQuery(
                    "SELECT var_id FROM variantes WHERE var_prd_id = :prdId AND LOWER(var_color) = LOWER(:color) LIMIT 1")
                    .setParameter("prdId", prdId)
                    .setParameter("color", color)
                    .getSingleResult()).longValue();
        } catch (NoResultException e) {
            return null; // el color ya no existe entre las variantes actuales — se guarda como "todos"
        }
    }

    private ProductoResponse toResponse(Producto p) {
        List<Variante> variantesRaw = varianteRepo.findByPrdIdOrderByIdAsc(p.getId());
        Map<Long, String> colorPorVarId = new HashMap<>();
        for (Variante v : variantesRaw) colorPorVarId.put(v.getId(), v.getColor());

        List<VarianteResponse> variantes = variantesRaw.stream().map(this::toVarianteResponse).toList();
        List<ImagenResponse> imagenes = imagenRepo.findByPrdIdOrderByOrdenAscIdAsc(p.getId())
                .stream().map(img -> new ImagenResponse(img.getId(), img.getUrl(), img.getOrden(), img.getTipo(),
                        img.getVarId() != null ? colorPorVarId.get(img.getVarId()) : null)).toList();

        Long precio = p.getPrecioDescuentoCentavos() != null
                ? p.getPrecioDescuentoCentavos() / 100L
                : p.getPrecioCentavos() / 100L;
        Long precioAntes = p.getPrecioDescuentoCentavos() != null
                ? p.getPrecioCentavos() / 100L
                : null;

        int stockTotal = variantes.stream()
                .filter(vr -> Boolean.TRUE.equals(vr.activo()))
                .mapToInt(VarianteResponse::stock)
                .sum();

        return new ProductoResponse(
                p.getId(),
                p.getCatId(),
                p.getSubId(),
                p.getNombre(),
                p.getSlug(),
                p.getDescripcion(),
                precio,
                precioAntes,
                p.getOfertaHasta(),
                p.getFichaTecnica() != null ? p.getFichaTecnica() : Collections.emptyMap(),
                p.isActivo(),
                p.getCreadoEn(),
                stockTotal,
                variantes,
                imagenes
        );
    }

    private VarianteResponse toVarianteResponse(Variante v) {
        String estadoStock = v.getStock() == 0 ? "agotado" : v.getStock() <= 5 ? "bajo" : "disponible";
        return new VarianteResponse(v.getId(), v.getTalla(), v.getColor(), v.getColorHex(),
                v.getStock(), v.isActivo(), estadoStock);
    }
}
