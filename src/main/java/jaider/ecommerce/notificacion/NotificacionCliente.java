package jaider.ecommerce.notificacion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Notificación de un evento de negocio dirigida a un cliente puntual de la tienda. */
@Entity
@Table(name = "notificaciones")
@Getter
@Setter
public class NotificacionCliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ntf_id")
    private Long id;

    @Column(name = "ntf_usr_id", nullable = false, updatable = false)
    private Long usrId;

    // tipo_notificacion es un enum personalizado de PostgreSQL — se mapea como String
    @Column(name = "ntf_tipo", nullable = false, updatable = false, columnDefinition = "tipo_notificacion")
    private String tipo;

    @Column(name = "ntf_titulo", nullable = false, updatable = false, length = 191)
    private String titulo;

    @Column(name = "ntf_cuerpo", updatable = false)
    private String cuerpo;

    @Column(name = "ntf_entidad_tipo", updatable = false, length = 30)
    private String entidadTipo;

    @Column(name = "ntf_entidad_id", updatable = false)
    private Long entidadId;

    @Column(name = "ntf_imagen_url", updatable = false, length = 512)
    private String imagenUrl;

    @Column(name = "ntf_leida_en")
    private OffsetDateTime leidaEn;

    @Column(name = "ntf_eliminada_en")
    private OffsetDateTime eliminadaEn;

    @Column(name = "ntf_creado_en", insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
