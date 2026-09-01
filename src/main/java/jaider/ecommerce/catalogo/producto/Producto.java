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

    // Referencia OPCIONAL al empaque (caja) en que se envía este producto — PLAN_INTEGRACION_
    // ENVIA.md, Fase 1. Un producto NO tiene peso/dimensiones propias: "las cajas son las que
    // tienen que tener las medidas... no se mide al zapato, se mide la caja" (decisión explícita
    // del usuario) — el peso también va en el empaque, no en el producto. Mismo patrón que
    // prd_cat_id/prd_sub_id (FK simple, no un @ManyToOne de JPA). Ningún producto existente la
    // necesita; solo se exige si la tienda activa el envío calculado con Envia (hoy bloqueado,
    // ver TiendaConfigService).
    @Column(name = "prd_empaque_id")
    private Long empaqueId;

    @Column(name = "prd_oferta_hasta")
    private java.time.OffsetDateTime ofertaHasta;

    // prd_tsv NO se mapea: tsvector gestionado por trigger

    @Column(name = "prd_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "prd_actualizado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
