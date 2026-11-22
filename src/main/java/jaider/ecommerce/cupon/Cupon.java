package jaider.ecommerce.cupon;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** Cupón de descuento de UNA tienda ("bono de compra") — PENDIENTE_DUO_CHIC. El admin de la
 *  tienda lo crea con código aleatorio o personalizado, de un solo uso o con límite de usos; el
 *  cliente lo aplica en el carrito antes de pagar (ver CuponService.canjear). */
@Entity
@Table(name = "cupones")
@Getter
@Setter
public class Cupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cup_id")
    private Long id;

    @Column(name = "cup_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "cup_codigo", nullable = false, updatable = false, length = 30)
    private String codigo;

    @Column(name = "cup_tipo_descuento", nullable = false, updatable = false, length = 10)
    private String tipoDescuento; // "porcentaje" | "fijo"

    @Column(name = "cup_valor", nullable = false, updatable = false)
    private Long valor;

    @Column(name = "cup_max_usos", nullable = false, updatable = false)
    private int maxUsos;

    @Column(name = "cup_usos_actuales", nullable = false)
    private int usosActuales;

    @Column(name = "cup_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "cup_creado_por", updatable = false)
    private Long creadoPor;

    @Column(name = "cup_creado_en", insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
