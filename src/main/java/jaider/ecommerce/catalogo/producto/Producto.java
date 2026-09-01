package jaider.ecommerce.catalogo.producto;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "productos")
@Getter
@Setter
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prd_id")
    private Long id;

    @Column(name = "prd_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "prd_cat_id", nullable = false)
    private Long catId;

    @Column(name = "prd_sub_id")
    private Long subId;

    @Column(name = "prd_nombre", nullable = false, length = 191)
    private String nombre;

    @Column(name = "prd_slug", nullable = false, length = 191)
    private String slug;

    @Column(name = "prd_descripcion", columnDefinition = "text")
    private String descripcion;

    @Column(name = "prd_precio_centavos", nullable = false)
    private Long precioCentavos;

    @Column(name = "prd_precio_descuento_centavos")
    private Long precioDescuentoCentavos;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prd_ficha_tecnica", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> fichaTecnica = Map.of();

    @Column(name = "prd_activo", nullable = false)
    private boolean activo = true;

    // Referencia OPCIONAL a una especificación logística reutilizable (peso + dimensiones) —
    // PLAN_INTEGRACION_ENVIA.md, Fase 1. Normalizada a propósito: muchos productos comparten
    // exactamente el mismo peso/tamaño físico (ej. varios modelos de tenis "estándar"), así que
    // el peso/dimensiones viven en su propia tabla (especificaciones_logisticas) y el admin la
    // crea UNA vez y la reusa en cuantos productos quiera — mismo patrón que prd_cat_id/prd_sub_id
    // (una FK simple, no un @ManyToOne de JPA, siguiendo la convención del resto de esta clase).
    // Ningún producto existente la necesita; solo se exige si la tienda activa el envío calculado
    // con Envia (hoy bloqueado, ver TiendaConfigService).
    @Column(name = "prd_especificacion_id")
    private Long especificacionId;

    @Column(name = "prd_oferta_hasta")
    private java.time.OffsetDateTime ofertaHasta;

    // prd_tsv NO se mapea: tsvector gestionado por trigger

    @Column(name = "prd_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "prd_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
