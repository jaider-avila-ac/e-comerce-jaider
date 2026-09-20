package jaider.ecommerce.geo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de departamentos/municipios de Colombia (DANE/DIVIPOLA) — antes vivía SOLO como un
 * archivo JS estático dentro del frontend de la tienda (`colombiaGeo.js`), lo cual significaba
 * que (a) cada tienda nueva tendría que copiar/mantener su propia versión y (b) el backend no
 * podía validar nada contra él: cualquier string llegaba como "departamento"/"municipio" válido.
 * Corregido: única fuente de verdad acá, servida vía {@link PublicGeoController} para que
 * cualquier frontend (tienda, admin, futuras tiendas del multi-tenant) lo consuma en vez de
 * cargarlo por su cuenta.
 *
 * Es un recurso estático embebido en el JAR (no una tabla) a propósito: son datos de referencia
 * de Colombia que prácticamente no cambian — no son datos de negocio de ningún tenant, así que
 * no aplican las reglas de RLS/tenant de las demás tablas.
 */
@Slf4j
@Service
public class ColombiaGeoService {

    private final Map<String, List<String>> catalogo;

    public ColombiaGeoService(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("data/colombia-geo.json").getInputStream()) {
            this.catalogo = objectMapper.readValue(in, new TypeReference<Map<String, List<String>>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar data/colombia-geo.json", e);
        }
        log.info("[ColombiaGeo] catálogo cargado: {} departamentos, {} municipios",
                catalogo.size(), catalogo.values().stream().mapToInt(List::size).sum());
    }

    public Map<String, List<String>> catalogo() {
        return catalogo;
    }

    public boolean esDepartamentoValido(String departamento) {
        return departamento != null && catalogo.containsKey(departamento.trim());
    }

    public boolean esMunicipioValido(String departamento, String municipio) {
        if (departamento == null || municipio == null) return false;
        List<String> municipios = catalogo.get(departamento.trim());
        return municipios != null && municipios.contains(municipio.trim());
    }
}
