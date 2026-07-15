package jaider.ecommerce.pedido.devolucion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "solicitudes_devolucion")
@Getter
@Setter
public class SolicitudDevolucion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sod_id")
    private Long id;

    @Column(name = "sod_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "sod_ped_id", nullable = false, updatable = false)
    private Long pedId;

    // estado_devolucion es un enum personalizado de PostgreSQL — se mapea como String.
    // insertable = false: el INSERT vía JPA no puede pasar un varchar donde Postgres espera
    // el enum sin CAST explícito, así que se deja que la columna tome su DEFAULT ('pendiente')
    // al crear; cualquier cambio posterior de estado pasa por un UPDATE nativo con CAST
    // (ver SolicitudDevolucionRepository), nunca por un save() de esta entidad.
    @Column(name = "sod_estado", nullable = false, columnDefinition = "estado_devolucion", insertable = false)
    private String estado;

    @Column(name = "sod_motivo", nullable = false, columnDefinition = "text")
    private String motivo;

    @Column(name = "sod_dvd_id")
    private Long dvdId;

    @Column(name = "sod_codigo_rastreo", length = 100)
    private String codigoRastreo;

    @Column(name = "sod_admin_nota", columnDefinition = "text")
    private String adminNota;

    @Column(name = "sod_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "sod_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;

    @Column(name = "sod_revisado_en")
    private OffsetDateTime revisadoEn;

    @Column(name = "sod_recibida_en")
    private OffsetDateTime recibidaEn;
}
