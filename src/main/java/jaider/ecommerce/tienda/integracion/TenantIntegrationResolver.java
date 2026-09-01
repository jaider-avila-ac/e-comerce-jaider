package jaider.ecommerce.tienda.integracion;

import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.tienda.secretos.TenantSecretCache;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resuelve las credenciales de las integraciones externas (Cloudinary, Resend, Wompi, Envia) de
 * UNA tienda a partir de su {@code tnd_id} — ver
 * PLAN_MEJORAS_API_ECOMMERCE_MULTITENANT.md §6.3.
 *
 * Dos fuentes posibles, en este orden (decisión explícita del usuario, 2026-08-31):
 *   1. {@code tienda_secretos} (cifrada, vía {@link TenantSecretCache}) — la fuente para
 *      cualquier tienda creada/configurada desde el panel de superadmin.
 *   2. Variable de entorno {@code <PROVEEDOR>_<ALIAS>_<CAMPO>} (ej.
 *      {@code CLOUDINARY_CALZADO_CARIBE_API_SECRET}) — se mantiene por compatibilidad con
 *      tiendas que ya se configuraron así (Calzacaribe) y para las que el operador siga
 *      prefiriendo ese camino manual.
 * Cada tienda tiene un {@code tnd_secret_alias} (alias neutral, ver
 * {@link Tienda#getSecretAlias()}) que arma el nombre de esa variable — este resolver es el
 * único punto del código que lo hace. El resto de la app solo pide "las credenciales de
 * Cloudinary del tenant X" y recibe un objeto inmutable, sin saber de dónde salieron.
 */
@Component
@RequiredArgsConstructor
public class TenantIntegrationResolver {

    private final TiendaRepository tiendaRepo;
    private final Environment environment;
    private final TenantSecretCache secretCache;

    public CloudinaryCredentials mediaCredentials(Long tndId) {
        String alias = resolveAlias(tndId);
        return new CloudinaryCredentials(
                requireField(tndId, alias, "CLOUDINARY", "CLOUD_NAME"),
                requireField(tndId, alias, "CLOUDINARY", "API_KEY"),
                requireField(tndId, alias, "CLOUDINARY", "API_SECRET"));
    }

    public ResendCredentials emailCredentials(Long tndId) {
        String alias = resolveAlias(tndId);
        return new ResendCredentials(
                requireField(tndId, alias, "RESEND", "API_KEY"),
                requireField(tndId, alias, "RESEND", "FROM"));
    }

    /**
     * La llave privada es la única opcional de las 4 (igual que antes de esta refactorización):
     * una tienda puede tener el checkout hospedado de Wompi funcionando (public+integrity key)
     * sin haber activado cobro directo con tarjeta tokenizada ni reembolsos automáticos — esos
     * flujos ya validaban en tiempo de uso que la llave privada estuviera presente y fallaban con
     * un mensaje claro si no, en vez de exigirla desde el arranque.
     */
    public WompiCredentials paymentCredentials(Long tndId) {
        String alias = resolveAlias(tndId);
        return new WompiCredentials(
                requireField(tndId, alias, "WOMPI", "PUBLIC_KEY"),
                optionalField(tndId, alias, "WOMPI", "PRIVATE_KEY"),
                requireField(tndId, alias, "WOMPI", "INTEGRITY_KEY"),
                requireField(tndId, alias, "WOMPI", "EVENTS_KEY"));
    }

    /** PLAN_INTEGRACION_ENVIA.md, Fase 2 — token único (no hay llave pública/privada separada
     *  como en Wompi). El ambiente (sandbox/producción) NO es un secreto, vive en
     *  {@code tiendas.tnd_envia_ambiente} (ver Tienda), este resolver solo se encarga del token. */
    public EnviaCredentials envioCredentials(Long tndId) {
        String alias = resolveAlias(tndId);
        return new EnviaCredentials(requireField(tndId, alias, "ENVIA", "API_TOKEN"));
    }

    private String resolveAlias(Long tndId) {
        return tiendaRepo.findById(tndId)
                .map(Tienda::getSecretAlias)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo resolver el alias de secretos: tienda " + tndId + " no existe"));
    }

    /** BD cifrada primero, variable de entorno como respaldo — ver el javadoc de la clase. */
    private String requireField(Long tndId, String alias, String proveedor, String campo) {
        return desdeBdOEnv(tndId, alias, proveedor, campo)
                .orElseThrow(() -> new IllegalStateException(
                        "Falta configurar " + proveedor + "_" + campo + " para esta tienda"
                                + " (ni en tienda_secretos ni en la variable de entorno "
                                + proveedor + "_" + alias + "_" + campo + ")"));
    }

    /** Como requireField, pero devuelve "" en vez de fallar si no está configurado — para campos
     *  que un flujo puede necesitar validar por su cuenta en el momento de usarlos, no acá. */
    private String optionalField(Long tndId, String alias, String proveedor, String campo) {
        return desdeBdOEnv(tndId, alias, proveedor, campo).orElse("");
    }

    private Optional<String> desdeBdOEnv(Long tndId, String alias, String proveedor, String campo) {
        Optional<String> desdeBd = secretCache.get(tndId, proveedor, campo);
        if (desdeBd.isPresent() && !desdeBd.get().isBlank()) return desdeBd;

        String desdeEnv = environment.getProperty(proveedor + "_" + alias + "_" + campo);
        return (desdeEnv != null && !desdeEnv.isBlank())
                ? Optional.of(desdeEnv)
                : Optional.empty();
    }
}
