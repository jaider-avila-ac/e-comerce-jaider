package jaider.ecommerce.tienda.envio;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arma las partes del payload de Envia que {@link EnviaRateClient} y {@link EnviaLabelClient}
 * necesitan de forma idéntica ({@code origin}/{@code destination}/{@code packages}) — extraído a
 * un solo lugar (corrección de auditoría, 2026-09-01) para que la corrección del valor declarado
 * duplicado no dependiera de mantener dos copias sincronizadas.
 */
final class EnviaPayloadHelper {

    private EnviaPayloadHelper() {}

    /** Corrección de auditoría: {@code RestClient.create()} sin más no tiene ningún timeout —
     *  una respuesta lenta o colgada de Envia podía retener un hilo (y, peor, la transacción de
     *  BD que envuelve la llamada) indefinidamente. 8s para conectar, 20s para la respuesta —
     *  Envia documenta respuestas típicamente rápidas para cotizar/rastrear; generar guía puede
     *  tardar algo más, pero nunca debería colgarse. */
    static RestClient clienteConTimeout() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8_000);
        factory.setReadTimeout(20_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    static Map<String, Object> direccionPayload(DireccionEnvia dir, GeocodeResultado geo, String carrier) {
        // Servientrega exige el código DANE de 8 dígitos como "city"; las demás transportadoras
        // usan el nombre real de la ciudad que devuelve Geocodes (no el que escribió el cliente).
        String city = "servientrega".equals(carrier) ? geo.stat8Digit() : geo.locality();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", dir.nombre());
        m.put("phone", dir.telefono());
        m.put("street", dir.calle());
        m.put("city", city);
        m.put("state", geo.state3());
        m.put("country", "CO");
        m.put("postalCode", dir.codigoPostal());
        return m;
    }

    /** Corrección de auditoría: antes se repetía el valor TOTAL declarado en cada renglón de
     *  packages[] — con 3 empaques distintos se declaraban 3 veces el valor real del pedido. Se
     *  reparte proporcional a la cantidad de artículos de cada renglón, y el último absorbe el
     *  residuo de la división entera para que la SUMA sea exacta. */
    static List<Map<String, Object>> paquetesPayload(List<PaqueteCalculado> paquetes, long declaredValueCop) {
        List<Map<String, Object>> lista = new ArrayList<>();
        int totalArticulos = paquetes.stream().mapToInt(PaqueteCalculado::cantidad).sum();
        long acumulado = 0;
        for (int i = 0; i < paquetes.size(); i++) {
            PaqueteCalculado p = paquetes.get(i);
            long valorRenglon;
            if (i == paquetes.size() - 1) {
                valorRenglon = declaredValueCop - acumulado;
            } else {
                valorRenglon = totalArticulos > 0 ? declaredValueCop * p.cantidad() / totalArticulos : 0;
                acumulado += valorRenglon;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("content", p.empaqueNombre());
            m.put("amount", p.cantidad());
            m.put("type", "box");
            // Corrección de auditoría (2026-09-01, tercera vuelta): el Math.max(1, ...) anterior
            // forzaba un mínimo de 1 KG por renglón sin ninguna justificación documentada — un
            // empaque real de 150g (habitual en calzado/ropa liviana) se cotizaba y cobraba como
            // si pesara 1000g, 6.6× más de lo real. Se manda el peso real configurado, sin piso
            // artificial (el peso mínimo real de Envia, si existe, es cosa de su propia API).
            m.put("weight", p.pesoGramosPorUnidad() / 1000.0);
            m.put("weightUnit", "KG");
            m.put("lengthUnit", "CM");
            m.put("dimensions", Map.of("length", p.largoCm(), "width", p.anchoCm(), "height", p.altoCm()));
            m.put("declaredValue", valorRenglon);
            lista.add(m);
        }
        return lista;
    }
}
