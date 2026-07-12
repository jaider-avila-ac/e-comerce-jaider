package jaider.ecommerce.catalogo.resena;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "reseñas")
@Getter
@Setter
public class Resena {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "res_id")
    private Long id;

    @Column(name = "res_prd_id", nullable = false, updatable = false)
    private Long prdId;

    @Column(name = "res_usr_id", nullable = false, updatable = false)
    private Long usrId;

    @Column(name = "res_pi_id", updatable = false)
    private Long piId;

    @Column(name = "res_calificacion", nullable = false)
    private Integer calificacion;

    @Column(name = "res_titulo", length = 191)
    private String titulo;

    @Column(name = "res_cuerpo")
    private String cuerpo;

    @Column(name = "res_aprobada", nullable = false)
    private boolean aprobada = true;

    @Column(name = "res_creado_en", insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
