package jaider.ecommerce.notificacion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Notificación de un evento de negocio dirigida a los admins de una tienda (broadcast por tnd_id). */
@Entity
@Table(name = "notificaciones_admin")
@Getter
@Setter
public class NotificacionAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "nta_id")
    private Long id;

    @Column(name = "nta_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    // tipo_notificacion_admin es un enum personalizado de PostgreSQL — se mapea como String
    @Column(name = "nta_tipo", nullable = false, updatable = false, columnDefinition = "tipo_notificacion_admin")
    private String tipo;

    @Column(name = "nta_titulo", nullable = false, updatable = false, length = 191)
    private String titulo;

    @Column(name = "nta_cuerpo", updatable = false)
    private String cuerpo;

    @Column(name = "nta_entidad_tipo", updatable = false, length = 30)
    private String entidadTipo;

    @Column(name = "nta_entidad_id", updatable = false)
    private Long entidadId;

    @Column(name = "nta_leida_en")
    private OffsetDateTime leidaEn;

    @Column(name = "nta_creado_en", insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
