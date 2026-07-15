package jaider.ecommerce.pedido.devolucion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "solicitud_devolucion_fotos")
@Getter
@Setter
public class SolicitudDevolucionFoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sdf_id")
    private Long id;

    @Column(name = "sdf_sod_id", nullable = false, updatable = false)
    private Long sodId;

    @Column(name = "sdf_url", nullable = false, length = 512)
    private String url;

    @Column(name = "sdf_orden", nullable = false)
    private Short orden = 0;

    @Column(name = "sdf_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
