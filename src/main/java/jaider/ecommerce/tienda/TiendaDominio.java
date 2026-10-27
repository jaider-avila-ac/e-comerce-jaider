package jaider.ecommerce.tienda;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Dominio público registrado para una tienda (PLAN_MEJORAS_API_ECOMMERCE_MULTITENANT.md §5).
 * Permite resolver el tenant de una solicitud pública a partir del Host real, en vez de confiar
 * únicamente en X-Tenant-Id (manipulable por el navegador).
 */
@Entity
@Table(name = "tienda_dominios")
@Getter @Setter
public class TiendaDominio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tdo_id")
    private Long id;

    @Column(name = "tdo_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "tdo_dominio", nullable = false, unique = true, length = 255)
    private String dominio;

    @Column(name = "tdo_principal", nullable = false)
    private boolean principal;

    // El dominio "principal" es el de marca (el que la gente reconoce); el que de verdad
    // reenvía /api/** hacia el backend puede ser otro — ej. Duo Chic: duochicstudio.com es
    // principal pero es hosting estático sin proxy, api.duochicstudio.com es el que sirve la
    // API. Para URLs que el admin de la tienda vaya a pegar en otro sistema (webhooks de
    // Wompi/Envia) hay que usar ESTE dominio, nunca el principal a ciegas.
    @Column(name = "tdo_sirve_api", nullable = false)
    private boolean sirveApi;

    // Independiente de sirveApi: cuál dominio sirve de verdad el SPA del cliente final (a dónde
    // debe volver su navegador, ej. al terminar de pagar en Wompi). Para tiendas con un solo
    // dominio que hace todo (Calzacaribe) coincide con sirveApi; para Duo Chic el frontend está
    // en el principal (duochicstudio.com) y sirveApi es OTRO dominio (api.duochicstudio.com); para
    // Jey Avila es al revés: el frontend real es tienda.jeyavilamusic.com (que también sirveApi),
    // mientras que jeyavilamusic.com (el "principal" de marca) es el sitio del cantante, no la
    // tienda. Ningún combo de principal/sirveApi solo basta para los tres casos (bug encontrado
    // 2026-10-26: Wompi devolvía al cliente de Duo Chic a api.duochicstudio.com, que no sirve el
    // SPA en absoluto).
    @Column(name = "tdo_sirve_frontend", nullable = false)
    private boolean sirveFrontend;

    @Column(name = "tdo_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "tdo_verificado_en")
    private OffsetDateTime verificadoEn;

    @Column(name = "tdo_creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
