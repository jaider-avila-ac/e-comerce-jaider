package jaider.ecommerce.catalogo.publico;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jaider.ecommerce.catalogo.categoria.Categoria;
import jaider.ecommerce.catalogo.categoria.CategoriaRepository;
import jaider.ecommerce.catalogo.producto.*;
import jaider.ecommerce.catalogo.resena.ResenaService;
import jaider.ecommerce.catalogo.subcategoria.Subcategoria;
import jaider.ecommerce.catalogo.subcategoria.SubcategoriaRepository;
import jaider.ecommerce.shared.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PublicCatalogService {

    private final CategoriaRepository catRepo;
    private final SubcategoriaRepository subRepo;
    private final ProductoRepository prodRepo;
    private final VarianteRepository varianteRepo;
    private final ProductoImagenRepository imagenRepo;
    private final TenantSupport tenantSupport;
    private final ResenaService resenaService;

    @Value("${app.base-url}")
    private String baseUrl;

    @PersistenceContext
    private EntityManager em;

    // ─── Categorías ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PublicCategoriaResponse> getCategorias() {
        tenantSupport.applyTenant(em);
        return catRepo.findAllByOrderByOrdenAscNombreAsc().stream()
                .filter(Categoria::isActivo)
                .map(cat -> {
                    List<String> subcats = subRepo.findByCatIdOrderByOrdenAscNombreAsc(cat.getId())
                            .stream()
                            .filter(Subcategoria::isActivo)
                            .map(Subcategoria::getNombre)
                            .toList();
                    // Si la categoría no tiene imagen propia, usar la primera imagen de sus productos
                    String imagenUrl = (cat.getImagenUrl() != null && !cat.getImagenUrl().isBlank())
                            ? cat.getImagenUrl()
                            : imagenRepo.findFirstImageUrlByCatId(cat.getId()).orElse(null);
                    return new PublicCategoriaResponse(
                            cat.getId(), cat.getNombre(), cat.getSlug(), imagenUrl, subcats
                    );
                })
                .toList();
    }

    // ─── Productos ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PublicProductoResponse> getProductos(Long catId, String q) {
        tenantSupport.applyTenant(em);

        List<Producto> lista = (catId != null)
                ? prodRepo.findByCatId(catId)
                : prodRepo.findActivos();

        if (q != null && !q.isBlank()) {
            String lq = q.toLowerCase();
            lista = lista.stream().filter(p ->
                    p.getNombre().toLowerCase().contains(lq) ||
                    (p.getDescripcion() != null && p.getDescripcion().toLowerCase().contains(lq)) ||
                    marcaOf(p).toLowerCase().contains(lq)
            ).toList();
        }

        return enrich(lista);
    }

    /**
     * Versión paginada — usada por el scroll infinito del catálogo en la tienda.
     * Mantiene el mismo criterio de "activo" que {@link #getProductos}: sin filtro
     * cuando se navega por categoría, solo activos cuando se ve el catálogo completo.
     */
    @Transactional(readOnly = true)
    public jaider.ecommerce.shared.dto.PageResponse<PublicProductoResponse> getProductosPaginado(
            Long catId, String q, int page, int size) {
        tenantSupport.applyTenant(em);

        Boolean activo = (catId != null) ? null : true;
        String qNorm = (q == null || q.isBlank()) ? null : q.trim();
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        var result = prodRepo.search(catId, activo, qNorm, pageable);

        List<PublicProductoResponse> content = enrich(result.getContent());
        return new jaider.ecommerce.shared.dto.PageResponse<>(
                content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private List<PublicProductoResponse> enrich(List<Producto> lista) {
        Set<Long> catIds = lista.stream().map(Producto::getCatId).collect(Collectors.toSet());
        Map<Long, Categoria> catMap = catRepo.findAllById(catIds).stream()
                .collect(Collectors.toMap(Categoria::getId, c -> c));
        Set<Long> subIds = lista.stream().filter(p -> p.getSubId() != null)
                .map(Producto::getSubId).collect(Collectors.toSet());
        Map<Long, Subcategoria> subMap = subRepo.findAllById(subIds).stream()
                .collect(Collectors.toMap(Subcategoria::getId, s -> s));

        // Carga masiva de "más vendidos" (top 20% o mínimo 3 ventas) para evitar N+1
        List<Long> prdIds = lista.stream().map(Producto::getId).toList();
        Set<Long> masVendidosIds = loadMasVendidosIds(prdIds);
        Map<Long, ResenaService.ResenaResumen> resumenes = resenaService.resumenBulk(prdIds);

        return lista.stream()
                .map(p -> toPublicResponse(p, catMap.get(p.getCatId()), subMap.get(p.getSubId()), masVendidosIds,
                        resumenes.get(p.getId())))
                .toList();
    }

    /**
     * Productos activos para una lista de IDs, en el mismo orden en que llegaron (ej. "vistos
     * recientemente": más reciente primero) y con el mismo shape que el resto del catálogo
     * público — nunca un formato aparte con campos incompletos.
     */
    @Transactional(readOnly = true)
    public List<PublicProductoResponse> getProductosByIds(List<Long> ids) {
        tenantSupport.applyTenant(em);
        if (ids.isEmpty()) return List.of();

        Map<Long, Producto> porId = prodRepo.findAllById(ids).stream()
                .filter(Producto::isActivo)
                .collect(Collectors.toMap(Producto::getId, p -> p));

        List<Producto> ordenados = ids.stream()
                .map(porId::get)
                .filter(Objects::nonNull)
                .toList();

        return enrich(ordenados);
    }

    @Transactional(readOnly = true)
    public PublicProductoResponse getProductoById(Long id) {
        tenantSupport.applyTenant(em);
        Producto p = prodRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
        Categoria cat = catRepo.findById(p.getCatId()).orElse(null);
        Subcategoria sub = p.getSubId() != null ? subRepo.findById(p.getSubId()).orElse(null) : null;
        Set<Long> masVendidosIds = loadMasVendidosIds(List.of(p.getId()));
        ResenaService.ResenaResumen resumen = resenaService.resumenBulk(List.of(p.getId())).get(p.getId());
        return toPublicResponse(p, cat, sub, masVendidosIds, resumen);
    }

    // ─── Transformación ────────────────────────────────────────────────────

    private PublicProductoResponse toPublicResponse(Producto p, Categoria cat, Subcategoria sub,
                                                    Set<Long> masVendidosIds, ResenaService.ResenaResumen resumen) {
        // Oferta vigente: tiene precio descuento Y (sin vencimiento O aún no venció)
        boolean ofertaVigente = p.getPrecioDescuentoCentavos() != null
                && p.getPrecioDescuentoCentavos() > 0
                && (p.getOfertaHasta() == null || p.getOfertaHasta().isAfter(OffsetDateTime.now()));

        long precioBase = p.getPrecioCentavos() / 100L;
        long precioFinal = precioBase;
        int descuento = 0;
        if (ofertaVigente) {
            precioFinal = p.getPrecioDescuentoCentavos() / 100L;
            descuento = (int) Math.round(
                    (1.0 - (double) p.getPrecioDescuentoCentavos() / p.getPrecioCentavos()) * 100
            );
        }

        Map<String, Object> ficha = p.getFichaTecnica() != null ? p.getFichaTecnica() : Map.of();
        String marca = strOf(ficha.get("marca"));
        String genero = normalizeGenero(strOf(ficha.get("genero")));

        // Etiquetas derivadas automáticamente — sin tabla en BD
        List<String> etiquetas = new ArrayList<>();
        if (p.getCreadoEn() != null && p.getCreadoEn().isAfter(OffsetDateTime.now().minusDays(7))) {
            etiquetas.add("nuevo");
        }
        if (ofertaVigente) {
            etiquetas.add("oferta");
        }
        if (masVendidosIds.contains(p.getId())) {
            etiquetas.add("mas-vendido");
        }

        List<ProductoImagen> mediaProducto = imagenRepo.findByPrdIdOrderByOrdenAscIdAsc(p.getId());

        List<PublicProductoResponse.ImagenInfo> imagenes = mediaProducto.stream()
                .filter(img -> !"video".equals(img.getTipo()))
                .map(img -> new PublicProductoResponse.ImagenInfo(
                        baseUrl + "/api/v1/public/media/producto/" + p.getTndId() + "/" + img.getId(),
                        img.getVarId()))
                .toList();

        String video = mediaProducto.stream()
                .filter(img -> "video".equals(img.getTipo()))
                .findFirst()
                .map(img -> baseUrl + "/api/v1/public/media/producto/" + p.getTndId() + "/" + img.getId())
                .orElse(null);

        List<Variante> vars = varianteRepo.findByPrdIdOrderByIdAsc(p.getId())
                .stream().filter(Variante::isActivo).toList();

        List<PublicProductoResponse.TiendaOpcion> tallasOpc = buildTallas(vars);
        List<PublicProductoResponse.TiendaVariante> variantes = buildVariantes(vars);
        List<PublicProductoResponse.StockVariante> stockVariantes = vars.stream()
                .map(v -> new PublicProductoResponse.StockVariante(
                        v.getId(), v.getTalla(), v.getColor(), v.getStock()
                ))
                .toList();
        int stockTotal = vars.stream().mapToInt(v -> v.getStock() != null ? v.getStock() : 0).sum();

        Map<String, Object> caracteristicas = new LinkedHashMap<>();
        ficha.forEach((k, v) -> {
            if (!k.equals("marca") && !k.equals("etiquetas")) {
                caracteristicas.put(capitalize(k), v);
            }
        });

        return new PublicProductoResponse(
                p.getId(),
                p.getNombre(),
                p.getSlug(),
                p.getDescripcion(),
                precioBase,
                precioFinal,
                descuento,
                marca,
                genero,
                p.getCatId(),
                cat != null ? cat.getNombre() : "",
                sub != null ? sub.getNombre() : "",
                etiquetas,
                p.isActivo(),
                imagenes,
                video,
                tallasOpc,
                variantes,
                stockVariantes,
                stockTotal,
                caracteristicas,
                resumen != null ? resumen.promedio() : null,
                resumen != null ? resumen.total() : null
        );
    }

    // ─── Más vendidos ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Set<Long> loadMasVendidosIds(List<Long> prdIds) {
        if (prdIds.isEmpty()) return Set.of();
        List<Object[]> rows = em.createNativeQuery(
                "SELECT pi_prd_id, SUM(pi_cantidad) AS total " +
                "FROM pedido_items " +
                "WHERE pi_prd_id IN :ids " +
                "GROUP BY pi_prd_id " +
                "HAVING SUM(pi_cantidad) >= 5 " +
                "ORDER BY total DESC")
                .setParameter("ids", prdIds)
                .getResultList();
        Set<Long> ids = new HashSet<>();
        for (Object[] r : rows) {
            ids.add(((Number) r[0]).longValue());
        }
        return ids;
    }

    // ─── Helpers de variantes ──────────────────────────────────────────────

    private List<PublicProductoResponse.TiendaOpcion> buildTallas(List<Variante> vars) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Variante v : vars) {
            if (v.getTalla() != null && !v.getTalla().isBlank()) {
                map.merge(v.getTalla(), v.getStock(), Integer::sum);
            }
        }
        return map.entrySet().stream()
                .map(e -> new PublicProductoResponse.TiendaOpcion(e.getKey(), e.getValue(), null, null))
                .toList();
    }

    private List<PublicProductoResponse.TiendaVariante> buildVariantes(List<Variante> vars) {
        List<PublicProductoResponse.TiendaVariante> result = new ArrayList<>();

        Map<String, Integer> tallaMap = new LinkedHashMap<>();
        for (Variante v : vars) {
            if (v.getTalla() != null && !v.getTalla().isBlank()) {
                tallaMap.merge(v.getTalla(), v.getStock(), Integer::sum);
            }
        }
        if (!tallaMap.isEmpty()) {
            List<PublicProductoResponse.TiendaOpcion> opc = tallaMap.entrySet().stream()
                    .map(e -> new PublicProductoResponse.TiendaOpcion(e.getKey(), e.getValue(), null, null))
                    .toList();
            result.add(new PublicProductoResponse.TiendaVariante("Talla", "talla", true, opc));
        }

        Map<String, int[]> colorMap  = new LinkedHashMap<>();
        Map<String, String> colorHex = new LinkedHashMap<>();
        Map<String, Long>   colorVar = new LinkedHashMap<>(); // primer var_id por color
        for (Variante v : vars) {
            if (v.getColor() != null && !v.getColor().isBlank()) {
                colorMap.merge(v.getColor(), new int[]{v.getStock()}, (a, b) -> {
                    a[0] += b[0];
                    return a;
                });
                colorHex.putIfAbsent(v.getColor(), v.getColorHex() != null ? v.getColorHex() : "#000000");
                colorVar.putIfAbsent(v.getColor(), v.getId()); // primer var_id de ese color
            }
        }
        if (!colorMap.isEmpty()) {
            List<PublicProductoResponse.TiendaOpcion> opc = colorMap.entrySet().stream()
                    .map(e -> new PublicProductoResponse.TiendaOpcion(
                            e.getKey(), e.getValue()[0], colorHex.get(e.getKey()), colorVar.get(e.getKey())
                    ))
                    .toList();
            result.add(new PublicProductoResponse.TiendaVariante("Color", "color", false, opc));
        }

        return result;
    }

    private String marcaOf(Producto p) {
        if (p.getFichaTecnica() == null) return "";
        return strOf(p.getFichaTecnica().get("marca"));
    }

    private static String strOf(Object o) {
        return o instanceof String s ? s : "";
    }

    private static String normalizeGenero(String g) {
        if (g == null || g.isBlank()) return "";
        return switch (g.toLowerCase()) {
            case "hombre"  -> "hombre";
            case "mujer"   -> "mujer";
            case "niño", "niña", "niños", "niñas" -> "niños";
            case "unisex"  -> "unisex";
            default        -> g.toLowerCase();
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
