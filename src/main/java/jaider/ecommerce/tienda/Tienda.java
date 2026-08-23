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

    @Column(name = "tnd_nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "tnd_logo_url", length = 512)
    private String logoUrl;

    @Column(name = "tnd_moneda", nullable = false, length = 3)
    private String moneda = "COP";

    @Column(name = "tnd_instagram", length = 255)
    private String instagram;

    @Column(name = "tnd_sitio_web", length = 255)
    private String sitioWeb;

    @Column(name = "tnd_tienda_url", length = 255)
    private String tiendaUrl;

    @Column(name = "tnd_whatsapp_principal", length = 20)
    private String whatsappPrincipal;

    @Column(name = "tnd_whatsapp_la_paz", length = 20)
    private String whatsappLaPaz;

    // 'contra_entrega' (default — el cliente paga el envío directo al transportador al recibir,
    // no se cobra nada de envío en el checkout online) | 'fijo' (se cobra tnd_envio_costo_centavos
    // en el checkout, con la excepción de envío gratis por monto mínimo de abajo). El precio real
    // del envío varía mucho y no hay integración con transportadoras que lo calcule automático,
    // así que "contra entrega" es el modo seguro por defecto — el admin activa "fijo" cuando
    // quiera controlar/cobrar un costo de envío específico.
    @Column(name = "tnd_envio_modo", nullable = false, length = 20)
    private String envioModo = "contra_entrega";

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

    @Column(name = "tnd_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "tnd_creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
