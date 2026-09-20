package jaider.ecommerce.tienda.envio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Orden de preferencia de transportadoras para cotizar con Envia.com, por tienda —
 * PLAN_INTEGRACION_ENVIA.md, Fase 3. Pedido explícito del usuario: "ese orden lo pueda decidir
 * el administrador" — Servientrega por defecto primero, pero cada tienda puede cambiarlo.
 *
 * Si una tienda no tiene ninguna fila acá, {@link EnvioCotizacionService} usa un orden por
 * defecto razonable — esta tabla existe para que el admin lo AJUSTE, no para que sea
 * obligatoria configurarla antes de poder cotizar.
 */
@Entity
@Table(name = "tienda_transportadoras")
@Getter @Setter
public class TiendaTransportadora {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ttr_id")
    private Long id;

    @Column(name = "ttr_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    // Código real de carrier de Envia.com: "servientrega", "coordinadora", "interrapidisimo",
    // "envia" (Envía Colombia/Colvanes) — ver TransportadoraService.CARRIERS_VALIDOS.
    @Column(name = "ttr_carrier", nullable = false, length = 40)
    private String carrier;

    @Column(name = "ttr_orden", nullable = false)
    private short orden;

    @Column(name = "ttr_activo", nullable = false)
    private boolean activo = true;

    @Column(name = "ttr_creado_en", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
