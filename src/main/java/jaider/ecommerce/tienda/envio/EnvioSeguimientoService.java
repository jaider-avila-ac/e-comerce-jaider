package jaider.ecommerce.tienda.envio;

import com.fasterxml.jackson.databind.JsonNode;
import jaider.ecommerce.pedido.Pedido;
import jaider.ecommerce.pedido.PedidoRepository;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.tienda.Tienda;
import jaider.ecommerce.tienda.TiendaRepository;
import jaider.ecommerce.tienda.integracion.EnviaCredentials;
import jaider.ecommerce.tienda.integracion.TenantIntegrationResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Seguimiento detallado bajo demanda (tipo Mercado Libre) para un pedido de una tienda en modo
 * 'envia' — PLAN_INTEGRACION_ENVIA.md, Fase 5. Solo tiene sentido si el pedido ya tiene una guía
 * real generada (Fase 4, {@code ped_envia_shipment_id}); si no, no hay nada que rastrear con
 * Envia todavía — condicionado exactamente igual que el resto de las piezas de Envia. Ver
 * {@link EnviaTrackClient} para la forma real (verificada en vivo) de lo que devuelve.
 */
@Service
@RequiredArgsConstructor
public class EnvioSeguimientoService {

    private final TenantSupport tenantSupport;
    private final PedidoRepository pedidoRepo;
    private final TiendaRepository tiendaRepo;
    private final TenantIntegrationResolver integrationResolver;
    private final EnviaTrackClient trackClient;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public JsonNode seguimientoDetalle(Long usrId, Long tndId, String numero) {
        tenantSupport.requireTenant(em);

        Pedido pedido = pedidoRepo.findByNumeroAndUsrId(numero, usrId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));

        if (pedido.getEnviaShipmentId() == null || pedido.getCodigoRastreo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este pedido todavía no tiene una guía de Envia generada");
        }

        Tienda tienda = tiendaRepo.findById(tndId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no encontrada"));
        EnviaCredentials creds = integrationResolver.envioCredentials(tndId);
        String host = "produccion".equals(tienda.getEnviaAmbiente())
                ? "https://api.envia.com" : "https://api-test.envia.com";

        return trackClient.rastrear(host, creds.apiToken(), pedido.getCodigoRastreo());
    }
}
