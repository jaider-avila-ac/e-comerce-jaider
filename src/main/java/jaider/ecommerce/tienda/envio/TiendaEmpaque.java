package jaider.ecommerce.tienda.envio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Un tamaño/tipo de caja que la tienda usa para empacar pedidos (PLAN_INTEGRACION_ENVIA.md,
 * Fase 1) — ej. "Pequeña", "Mediana", "Grande". El rango [cantidadMin, cantidadMax] indica
 * cuántos artículos del carrito cubre esta caja; lo define cada tienda (no una regla fija tipo
 * "1 par = caja chica"), para que sirva para cualquier tipo de ecommerce, no solo zapatos.
 */
@Entity
@Table(name = "tienda_empaques")
@Getter
@Setter
public class TiendaEmpaque {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tep_id")
    private Long id;

    @Column(name = "tep_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "tep_nombre", nullable = false, length = 60)
    private String nombre;

    @Column(name = "tep_largo_cm", nullable = false)
    private Short largoCm;

    @Column(name = "tep_ancho_cm", nullable = false)
    private Short anchoCm;

    @Column(name = "tep_alto_cm", nullable = false)
    private Short altoCm;

    @Column(name = "tep_peso_gramos", nullable = false)
    private Integer pesoGramos;

    @Column(name = "tep_cantidad_min", nullable = false)
    private Integer cantidadMin = 1;

    /** null = sin límite superior. */
    @Column(name = "tep_cantidad_max")
    private Integer cantidadMax;

    @Column(name = "tep_orden", nullable = false)
    private Short orden = 0;

    @Column(name = "tep_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "tep_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
