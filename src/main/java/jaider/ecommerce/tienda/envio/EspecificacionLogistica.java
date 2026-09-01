package jaider.ecommerce.tienda.envio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Especificación logística reutilizable (peso + dimensiones) — PLAN_INTEGRACION_ENVIA.md,
 * Fase 1. El admin la crea UNA vez (ej. "Tenis estándar: 850g, 32x20x12cm") y la asigna a
 * cuantos productos comparten ese mismo peso/tamaño físico, en vez de repetir esos 4 valores en
 * cada producto ({@link jaider.ecommerce.catalogo.producto.Producto#getEspecificacionId()}).
 */
@Entity
@Table(name = "especificaciones_logisticas")
@Getter
@Setter
public class EspecificacionLogistica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "esl_id")
    private Long id;

    @Column(name = "esl_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "esl_nombre", nullable = false, length = 80)
    private String nombre;

    @Column(name = "esl_peso_gramos", nullable = false)
    private Integer pesoGramos;

    @Column(name = "esl_largo_cm", nullable = false)
    private Short largoCm;

    @Column(name = "esl_ancho_cm", nullable = false)
    private Short anchoCm;

    @Column(name = "esl_alto_cm", nullable = false)
    private Short altoCm;

    @Column(name = "esl_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "esl_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
