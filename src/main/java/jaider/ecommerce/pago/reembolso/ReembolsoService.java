package jaider.ecommerce.pago.reembolso;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.pago.dto.ResultadoReembolso;
import jaider.ecommerce.pago.service.PaymentGateway;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Reembolsos de dinero al cliente — origen compartido entre la cancelación de un pedido por el
 * admin y una devolución de producto aprobada. Intenta procesar el reembolso automáticamente
 * contra la pasarela; si no es posible (método no soportado, error de red, Wompi lo rechaza),
 * queda pendiente de gestión manual — nunca se marca "completado" sin una confirmación real.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReembolsoService {

    private static final Set<String> ESTADOS_MANUALES_VALIDOS = Set.of("completado", "rechazado", "error");

    private final ReembolsoRepository repo;
    private final PaymentGateway paymentGateway;
    private final TenantSupport tenantSupport;
    private final AuditoriaService auditoriaService;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public Long crear(Long pagId, Long pedId, Long usrId, long montoCentavos, String motivo, String origen) {
        tenantSupport.applyTenant(em);
        Number idNum = (Number) em.createNativeQuery("""
                INSERT INTO reembolsos (ref_pag_id, ref_ped_id, ref_usr_id, ref_monto_centavos, ref_motivo, ref_origen)
                VALUES (:pagId, :pedId, :usrId, :monto, :motivo, :origen)
                RETURNING ref_id
                """)
                .setParameter("pagId", pagId)
                .setParameter("pedId", pedId)
                .setParameter("usrId", usrId)
                .setParameter("monto", montoCentavos)
                .setParameter("motivo", motivo)
                .setParameter("origen", origen)
                .getSingleResult();
        return idNum.longValue();
    }

    /** Nunca lanza — cualquier fallo deja el reembolso listo para gestión manual del admin
     *  en vez de romper el flujo que lo originó (cancelación de pedido / devolución recibida). */
    @Transactional
    public void procesarAutomatico(Long refId) {
        tenantSupport.applyTenant(em);
        Reembolso r = repo.findById(refId).orElseThrow();

        Object[] pago;
        try {
            pago = (Object[]) em.createNativeQuery(
                    "SELECT pag_gateway_tx_id, pag_metodo::text FROM pagos WHERE pag_id = :id")
                    .setParameter("id", r.getPagId())
                    .getSingleResult();
        } catch (NoResultException e) {
            repo.actualizarProcesamiento(refId, "pendiente", null, null,
                    "No se encontró el pago asociado — requiere gestión manual", null);
            return;
        }
        String gatewayTxId = (String) pago[0];
        String metodo = (String) pago[1];

        // Solo se intenta automatizar para tarjeta — Wompi no expone anulación por API de forma
        // consistente para Nequi/PSE/transferencia; el resto siempre requiere gestión manual.
        if (!"CARD".equals(metodo)) {
            repo.actualizarProcesamiento(refId, "pendiente", null, null,
                    "Método de pago \"" + metodo + "\" — Wompi no soporta reembolso automático, gestiónalo manualmente", null);
            return;
        }

        ResultadoReembolso resultado = paymentGateway.reembolsar(gatewayTxId, r.getMontoCentavos());
        if (resultado.exitoso()) {
            repo.actualizarProcesamiento(refId, "completado", resultado.gatewayRefundId(),
                    resultado.respuestaJson(), null, OffsetDateTime.now());
        } else {
            repo.actualizarProcesamiento(refId, "pendiente", resultado.gatewayRefundId(),
                    resultado.respuestaJson(), resultado.mensaje(), null);
        }
    }

    @Transactional
    public void confirmarManual(Long refId, String nuevoEstado, String nota, Long adminId) {
        tenantSupport.applyTenant(em);
        Reembolso r = repo.findById(refId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reembolso no encontrado"));
        if (!ESTADOS_MANUALES_VALIDOS.contains(nuevoEstado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado inválido: " + nuevoEstado);
        }
        if ("completado".equals(r.getEstado()) || "rechazado".equals(r.getEstado())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este reembolso ya quedó cerrado");
        }

        OffsetDateTime ahora = OffsetDateTime.now();
        repo.confirmarManual(refId, nuevoEstado, nota, ahora);

        if (adminId != null) {
            Long tndId = ((Number) em.createNativeQuery("SELECT ped_tnd_id FROM pedidos WHERE ped_id = :id")
                    .setParameter("id", r.getPedId()).getSingleResult()).longValue();
            auditoriaService.registrar(tndId, adminId, "reembolso.confirmado_manual", "reembolso", refId,
                    Map.of("estado", nuevoEstado, "nota", nota == null ? "" : nota));
        }
    }
}
