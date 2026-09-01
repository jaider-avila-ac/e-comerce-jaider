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

/**
 * CRUD de las especificaciones logísticas reutilizables de la tienda actual —
 * PLAN_INTEGRACION_ENVIA.md, Fase 1. Mismo patrón de aislamiento por tenant que
 * {@link EmpaqueService}: {@link TenantSupport#requireTenant} + RLS de Postgres.
 */
@Service
@RequiredArgsConstructor
public class EspecificacionLogisticaService {

    private final EspecificacionLogisticaRepository repo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<EspecificacionLogisticaResponse> getAll() {
        tenantSupport.requireTenant(em);
        return repo.findAllByOrderByNombreAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public EspecificacionLogisticaResponse create(EspecificacionLogisticaRequest req) {
        tenantSupport.requireTenant(em);
        String tndId = TenantContext.get();
        if (tndId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");

        validarDatos(req);

        EspecificacionLogistica e = new EspecificacionLogistica();
        e.setTndId(Long.parseLong(tndId));
        e.setNombre(req.nombre().trim());
        e.setPesoGramos(req.pesoGramos());
        e.setLargoCm(req.largoCm());
        e.setAnchoCm(req.anchoCm());
        e.setAltoCm(req.altoCm());
        e.setActivo(req.activo() == null || req.activo());
        return toResponse(repo.save(e));
    }

    @Transactional
    public EspecificacionLogisticaResponse update(Long id, EspecificacionLogisticaRequest req) {
        tenantSupport.requireTenant(em);
        EspecificacionLogistica e = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Especificación no encontrada"));

        if (req.nombre() != null && !req.nombre().isBlank()) e.setNombre(req.nombre().trim());
        if (req.pesoGramos() != null) {
            if (req.pesoGramos() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El peso debe ser mayor a 0");
            e.setPesoGramos(req.pesoGramos());
        }
        if (req.largoCm() != null) {
            if (req.largoCm() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El largo debe ser mayor a 0");
            e.setLargoCm(req.largoCm());
        }
        if (req.anchoCm() != null) {
            if (req.anchoCm() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El ancho debe ser mayor a 0");
            e.setAnchoCm(req.anchoCm());
        }
        if (req.altoCm() != null) {
            if (req.altoCm() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El alto debe ser mayor a 0");
            e.setAltoCm(req.altoCm());
        }
        if (req.activo() != null) e.setActivo(req.activo());

        return toResponse(repo.save(e));
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.requireTenant(em);
        EspecificacionLogistica e = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Especificación no encontrada"));
        // ON DELETE SET NULL en la BD: los productos que la usaban simplemente quedan sin
        // especificación asignada, no se borran ni se bloquea el borrado.
        repo.delete(e);
    }

    private void validarDatos(EspecificacionLogisticaRequest req) {
        if (req.nombre() == null || req.nombre().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        if (req.pesoGramos() == null || req.pesoGramos() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El peso debe ser mayor a 0");
        }
        if (req.largoCm() == null || req.largoCm() <= 0
                || req.anchoCm() == null || req.anchoCm() <= 0
                || req.altoCm() == null || req.altoCm() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Largo, ancho y alto son obligatorios y deben ser mayores a 0");
        }
    }

    private EspecificacionLogisticaResponse toResponse(EspecificacionLogistica e) {
        return new EspecificacionLogisticaResponse(e.getId(), e.getNombre(), e.getPesoGramos(),
                e.getLargoCm(), e.getAnchoCm(), e.getAltoCm(), e.isActivo());
    }
}
