package jaider.ecommerce.tienda.aprovisionamiento;

import jaider.ecommerce.tienda.integracion.IntegracionSalud;

import java.util.List;

public record TenantProvisioningResult(
        Long tenantId,
        Long adminId,
        boolean activada,
        List<IntegracionSalud> saludIntegraciones,
        boolean aislamientoOk,
        String mensaje
) {}
