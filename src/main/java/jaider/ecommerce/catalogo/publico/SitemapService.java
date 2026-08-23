package jaider.ecommerce.catalogo.publico;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sitemap XML del catálogo público — para que Google (y demás buscadores) descubran cada
 * producto sin depender de que ejecuten el JavaScript de la tienda (es una SPA sin
 * server-side rendering: el HTML inicial no trae nada de un producto en particular, solo el
 * título/descripción genéricos de toda la tienda — ver ProductDetailPage.productSeoData, que
 * sí arma metadata completa por producto, pero solo después de que corre React en el navegador).
 *
 * Se genera en cada request (sin caché) directamente desde la tabla de productos, así que un
 * producto nuevo aparece acá de inmediato — no depende de ningún rebuild/deploy del frontend.
 * tienda/nginx.conf reenvía /sitemap.xml (en el dominio de la tienda) hacia este endpoint, para
 * que el archivo quede servido desde el mismo host que las páginas que lista — Google solo
 * acepta URLs de un sitemap si son del mismo host donde vive el archivo, salvo un cross-submit
 * verificado en Search Console, que no aplica acá.
 */
@Service
@RequiredArgsConstructor
public class SitemapService {

    // Deployment de un solo tenant — TenantInterceptor solo resuelve tenant por header
    // X-Tenant-Id, y un crawler nunca lo manda. Si algún día hay más de un tenant, esto
    // necesita resolverse por dominio en vez de quedar fijo en "1".
    private static final String TENANT_ID = "1";

    private final TenantSupport tenantSupport;

    @Value("${frontend.tienda-url}")
    private String tiendaUrl;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public String generar() {
        TenantContext.set(TENANT_ID);
        tenantSupport.applyTenant(em);
        String base = tiendaUrl.replaceAll("/+$", "");

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        agregarUrl(xml, base + "/", null, "daily", "1.0");
        agregarUrl(xml, base + "/catalogo", null, "daily", "0.8");

        // TO_CHAR ya formatea a ISO 8601 en el propio Postgres — evita depender de qué tipo
        // Java use el driver para timestamptz en una query nativa (Instant, OffsetDateTime,
        // Timestamp... varía y ya causó un bug silencioso acá: lastmod salía siempre vacío).
        List<Object[]> rows = em.createNativeQuery("""
                SELECT prd_id, TO_CHAR(prd_actualizado_en AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                FROM productos
                WHERE prd_activo = true
                ORDER BY prd_actualizado_en DESC
                """).getResultList();

        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            agregarUrl(xml, base + "/producto/" + id, (String) row[1], "weekly", "0.7");
        }

        xml.append("</urlset>\n");
        return xml.toString();
    }

    private void agregarUrl(StringBuilder xml, String loc, String lastmod, String changefreq, String priority) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (lastmod != null) xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
