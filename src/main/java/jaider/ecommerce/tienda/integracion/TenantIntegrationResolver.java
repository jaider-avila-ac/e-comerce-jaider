package jaider.ecommerce.tienda.integracion;

import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resuelve las credenciales de las integraciones externas (Cloudinary, y más adelante Resend
 * y Wompi/OnePay) de UNA tienda a partir de su {@code tnd_id} — ver
 * PLAN_MEJORAS_API_ECOMMERCE_MULTITENANT.md §6.3.
 *
 * Las llaves nunca están en código ni en la base de datos: cada tienda tiene un
 * {@code tnd_secret_alias} (alias neutral, ver {@link Tienda#getSecretAlias()}) y las llaves
 * reales viven en variables de entorno con el patrón {@code <PROVEEDOR>_<ALIAS>_<CAMPO>}
 * (ej. {@code CLOUDINARY_CALZADO_CARIBE_API_SECRET}). Este resolver es el único punto del
 * código que arma ese nombre de variable — el resto de la app solo pide "las credenciales de
 * Cloudinary del tenant X" y recibe un objeto inmutable, sin saber de dónde salieron.
 *
 * No cachea nada acá (el alias es una lectura de BD liviana y las variables de entorno ya
 * están en memoria del proceso) — el caché de recursos costosos de construir con estas
 * credenciales (como el cliente Cloudinary) vive en quien las consume, no en este resolver.
 */
@Component
@RequiredArgsConstructor
public class TenantIntegrationResolver {

    private final TiendaRepository tiendaRepo;
    private final Environment environment;

    public CloudinaryCredentials mediaCredentials(Long tndId) {
        String alias = resolveAlias(tndId);
        return new CloudinaryCredentials(
                requireEnv(alias, "CLOUDINARY", "CLOUD_NAME"),
                requireEnv(alias, "CLOUDINARY", "API_KEY"),
                requireEnv(alias, "CLOUDINARY", "API_SECRET"));
    }

    private String resolveAlias(Long tndId) {
        return tiendaRepo.findById(tndId)
                .map(Tienda::getSecretAlias)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo resolver el alias de secretos: tienda " + tndId + " no existe"));
    }

    private String requireEnv(String alias, String proveedor, String campo) {
        String key = proveedor + "_" + alias + "_" + campo;
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Falta la variable de entorno " + key + " — " + proveedor
                            + " no está configurado para esta tienda");
        }
        return value;
    }
}
