package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

/**
 * CRUD del orden de preferencia de transportadoras para cotizar con Envia.com —
 * PLAN_INTEGRACION_ENVIA.md, Fase 3. Pedido explícito del usuario: el orden (Servientrega
 * primero, etc.) lo decide el administrador de cada tienda, no queda fijo en el código — ver
 * {@link EnvioCotizacionService#ordenTransportadoras}, que usa esta tabla si tiene filas y cae
 * a un orden por defecto si está vacía.
 */
@Service
@RequiredArgsConstructor
public class TransportadoraService {

    /** Únicos 4 carriers reales que le interesan al negocio (confirmados cotizando o
     *  identificados en vivo contra la API real de Envia.com — ver memoria de la sesión). */
    static final Set<String> CARRIERS_VALIDOS = Set.of(
            "servientrega", "coordinadora", "interrapidisimo", "envia");

    private final TiendaTransportadoraRepository repo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<TransportadoraResponse> getAll() {
        tenantSupport.requireTenant(em);
        return repo.findAllByOrderByOrdenAscCarrierAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public TransportadoraResponse create(TransportadoraRequest req) {
        tenantSupport.requireTenant(em);
        String tndId = TenantContext.get();
        if (tndId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");

        String carrier = validarCarrier(req.carrier());
        if (repo.existsByCarrier(carrier)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta tienda ya tiene configurada la transportadora \"" + carrier + "\"");
        }

        TiendaTransportadora t = new TiendaTransportadora();
        t.setTndId(Long.parseLong(tndId));
        t.setCarrier(carrier);
        t.setOrden(req.orden() != null ? req.orden() : (short) 0);
        t.setActivo(req.activo() == null || req.activo());
        return toResponse(repo.save(t));
    }

    @Transactional
    public TransportadoraResponse update(Long id, TransportadoraRequest req) {
        tenantSupport.requireTenant(em);
        TiendaTransportadora t = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transportadora no encontrada"));

        if (req.carrier() != null) t.setCarrier(validarCarrier(req.carrier()));
        if (req.orden() != null) t.setOrden(req.orden());
        if (req.activo() != null) t.setActivo(req.activo());
        return toResponse(repo.save(t));
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.requireTenant(em);
        TiendaTransportadora t = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transportadora no encontrada"));
        repo.delete(t);
    }

    private String validarCarrier(String carrier) {
        if (carrier == null || carrier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La transportadora es obligatoria");
        }
        String limpio = carrier.trim().toLowerCase();
        if (!CARRIERS_VALIDOS.contains(limpio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transportadora inválida: " + carrier + " (válidas: " + CARRIERS_VALIDOS + ")");
        }
        return limpio;
    }

    private TransportadoraResponse toResponse(TiendaTransportadora t) {
        return new TransportadoraResponse(t.getId(), t.getCarrier(), t.getOrden(), t.isActivo());
    }
}
