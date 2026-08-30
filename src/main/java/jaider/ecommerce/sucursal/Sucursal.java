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

    @Column(name = "suc_creado_en", nullable = false, updatable = false)
    private OffsetDateTime creadoEn;
}
