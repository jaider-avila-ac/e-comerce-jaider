package jaider.ecommerce.tienda;

public record TiendaConfigRequest(
        String envioModo,       // "contra_entrega" | "fijo" | "envia"
        Boolean envioGratisActivo,
        Long envioGratisDesde, // pesos COP
        Long envioCosto,       // pesos COP — costo de envío estándar cuando no aplica envío gratis
        String dominioStaff,   // ej. "calzacaribe.com" — usado para armar el email de colaboradores
        String emailNotificacionPedidos, // recibe un correo cada vez que un cliente hace un pedido pagado
        // ── Identidad de marca (§4.1/§4.2/§8.3) — usada en el TenantBrandingContext de correos ──
        String razonSocial,
        String nit,
        String emailContacto,   // el que ve el comprador, distinto de emailNotificacionPedidos
        String colorPrincipal,  // hex "#RRGGBB"
        // ── PLAN_INTEGRACION_ENVIA.md, Fase 0 — solo importa si envioModo="envia" ──
        String enviaAmbiente    // "sandbox" | "produccion"
) {}
