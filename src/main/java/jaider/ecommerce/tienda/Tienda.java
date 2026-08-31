package jaider.ecommerce.tienda;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tiendas")
@Getter @Setter
public class Tienda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tnd_id")
    private Long id;

    @Column(name = "tnd_slug", nullable = false, unique = true, length = 60)
    private String slug;

    // Alias neutral e inmutable (mayúsculas/números/guion bajo) usado para resolver los
    // secretos de esta tienda: WOMPI_<alias>_*, RESEND_<alias>_*, CLOUDINARY_<alias>_* (ver
    // TenantIntegrationResolver). Nunca se expone al frontend ni contiene la llave en sí.
    @Column(name = "tnd_secret_alias", nullable = false, unique = true, length = 60)
    private String secretAlias;

    @Column(name = "tnd_nombre", nullable = false, length = 120)
    private String nombre;

    // Identidad legal (§4.1) — distinta del nombre comercial, para el pie de los correos
    // transaccionales y cualquier dato fiscal que deba mostrarse. Nullable: no toda tienda la
    // tiene cargada todavía (se completa desde el panel, TiendaConfigService).
    @Column(name = "tnd_razon_social", length = 200)
    private String razonSocial;

    @Column(name = "tnd_nit", length = 30)
    private String nit;

    @Column(name = "tnd_logo_url", length = 512)
    private String logoUrl;

    // Color de marca en hex (#RRGGBB) usado en las plantillas de correo (§8.3 TenantBrandingContext).
    @Column(name = "tnd_color_principal", length = 7)
    private String colorPrincipal;

    @Column(name = "tnd_moneda", nullable = false, length = 3)
    private String moneda = "COP";

    @Column(name = "tnd_instagram", length = 255)
    private String instagram;

    @Column(name = "tnd_sitio_web", length = 255)
    private String sitioWeb;

    @Column(name = "tnd_tienda_url", length = 255)
    private String tiendaUrl;

    // WhatsApp general de la tienda (§4.2) — no de una sucursal en particular. El contacto de
    // una sucursal específica va en Sucursal.whatsapp, no acá (antes existía
    // tnd_whatsapp_la_paz, exactamente el anti-patrón que el plan nombra: "no deben existir
    // campos específicos como whatsappLaPaz" — se eliminó, no lo usaba ni el backend ni ningún
    // frontend, columna/valor siempre vacíos).
    @Column(name = "tnd_whatsapp_principal", length = 20)
    private String whatsappPrincipal;

    // 'contra_entrega' (default — el cliente paga el envío directo al transportador al recibir,
    // no se cobra nada de envío en el checkout online) | 'fijo' (se cobra tnd_envio_costo_centavos
    // en el checkout, con la excepción de envío gratis por monto mínimo de abajo) | 'envia'
    // (PLAN_INTEGRACION_ENVIA.md — cotización real vía Envia.com, calculada del carrito; ningún
    // producto de Calzacaribe la necesita, es opcional por tienda). "contra entrega" sigue siendo
    // el modo seguro por defecto para cualquier tienda nueva.
    @Column(name = "tnd_envio_modo", nullable = false, length = 20)
    private String envioModo = "contra_entrega";

    // Solo importa si envioModo='envia' — a qué ambiente de Envia.com apunta esta tienda.
    // 'sandbox' por defecto a propósito: nunca se activa producción sin que el operador lo elija.
    @Column(name = "tnd_envia_ambiente", nullable = false, length = 20)
    private String enviaAmbiente = "sandbox";

    @Column(name = "tnd_envio_gratis_activo", nullable = false)
    private boolean envioGratisActivo = true;

    @Column(name = "tnd_envio_gratis_desde_centavos", nullable = false)
    private Long envioGratisDesdeCentavos = 20_000_000L;

    @Column(name = "tnd_envio_costo_centavos", nullable = false)
    private Long envioCostoCentavos = 1_200_000L;

    @Column(name = "tnd_dominio_staff", length = 120)
    private String dominioStaff;

    // Correo al que llega un aviso (vía Resend) cada vez que un cliente hace un pedido pagado.
    // Null/blank = no enviar ningún correo (además de la notificación in-app que ya existe).
    @Column(name = "tnd_email_notificacion_pedidos", length = 255)
    private String emailNotificacionPedidos;

    // Correo de atención al cliente (§4.2/§8.3) — el que ve el COMPRADOR en los correos
    // transaccionales. Distinto de emailNotificacionPedidos, que es para uso interno del staff.
    @Column(name = "tnd_email_contacto", length = 255)
    private String emailContacto;

    @Column(name = "tnd_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "tnd_creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
