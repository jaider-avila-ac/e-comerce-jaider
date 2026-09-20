package jaider.ecommerce.sucursal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

// Tienda física (mostrador) donde se atiende al cliente — no confundir con `Tienda`
// (paquete `tienda`), que es el tenant/negocio completo. Dos tiendas físicas comparten
// el mismo tenant, el mismo catálogo y el mismo panel admin; lo único que las distingue
// es a cuál pertenece cada colaborador y, por herencia, cada venta que gestiona.
@Entity
@Table(name = "sucursales")
@Getter @Setter
public class Sucursal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "suc_id")
    private Long id;

    @Column(name = "suc_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "suc_nombre", nullable = false, length = 80)
    private String nombre;

    @Column(name = "suc_activo", nullable = false)
    private boolean activo = true;

    // Contacto de ESTA sucursal en particular (§4.2 del plan multi-tenant: los contactos por
    // sucursal van acá, no como columnas fijas en `tiendas` tipo tnd_whatsapp_la_paz).
    @Column(name = "suc_whatsapp", length = 20)
    private String whatsapp;

    // Dirección de ORIGEN para envíos (desde dónde la transportadora recoge el paquete) —
    // PLAN_INTEGRACION_ENVIA.md, Fase 3. Va en `sucursales` y no en `tiendas` a propósito: una
    // tienda puede tener varias sucursales físicas, cada una un punto de recogida distinto.
    // Opcionales — todavía no hay un endpoint que las escriba (Fase 3 solo las deja disponibles,
    // la lógica que elige de cuál sucursal recoger es de una fase posterior).
    @Column(name = "suc_envio_origen_nombre", length = 150)
    private String envioOrigenNombre;

    @Column(name = "suc_envio_origen_telefono", length = 40)
    private String envioOrigenTelefono;

    @Column(name = "suc_envio_origen_direccion", length = 255)
    private String envioOrigenDireccion;

    @Column(name = "suc_envio_origen_complemento", length = 255)
    private String envioOrigenComplemento;

    @Column(name = "suc_envio_origen_departamento", length = 100)
    private String envioOrigenDepartamento;

    @Column(name = "suc_envio_origen_municipio", length = 100)
    private String envioOrigenMunicipio;

    @Column(name = "suc_envio_origen_codigo_postal", length = 10)
    private String envioOrigenCodigoPostal;

    @Column(name = "suc_creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
