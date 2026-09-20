package jaider.ecommerce.tienda.envio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Un empaque (caja) que la tienda usa para enviar sus productos — PLAN_INTEGRACION_ENVIA.md,
 * Fase 1. Junta peso Y dimensiones (nombre, peso, largo, ancho, alto): un producto no tiene
 * datos logísticos propios, se le asigna DIRECTO uno de estos empaques
 * ({@link jaider.ecommerce.catalogo.producto.Producto#getEmpaqueId()}) — decisión explícita del
 * usuario ("las cajas son las que tienen que tener las medidas, no se mide al zapato, se mide
 * la caja"; "el peso no va en el producto, va también en la caja"). Si dos productos comparten
 * caja pero pesan distinto, el admin crea dos empaques con las mismas medidas y distinto peso.
 *
 * La API real de Envia.com acepta un renglón por cada empaque distinto del carrito y ELLA
 * misma suma todo para cotizar (ver PaqueteCalculoService) — por eso esta tabla no necesita
 * ningún rango de "cuántos artículos cubre".
 *
 * Tope de 50cm por lado (ver CHECK de la BD) — límite real publicado por Coordinadora, para que
 * no se pueda crear un empaque que ninguna transportadora acepte.
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

    @Column(name = "tep_orden", nullable = false)
    private Short orden = 0;

    @Column(name = "tep_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "tep_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
