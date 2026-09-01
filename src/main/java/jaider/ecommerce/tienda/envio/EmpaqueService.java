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
 * CRUD del catálogo de empaques de la tienda actual (PLAN_INTEGRACION_ENVIA.md, Fase 1). Mismo
 * patrón de aislamiento por tenant que el resto de servicios de catálogo (CategoriaService,
 * etc.): {@link TenantSupport#requireTenant} al inicio de cada método, RLS de Postgres hace el
 * resto — un findById() de otra tienda simplemente no aparece, sin necesidad de un WHERE manual.
 */
@Service
@RequiredArgsConstructor
public class EmpaqueService {

    private final TiendaEmpaqueRepository repo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<EmpaqueResponse> getAll() {
        tenantSupport.requireTenant(em);
        return repo.findAllByOrderByOrdenAscNombreAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public EmpaqueResponse create(EmpaqueRequest req) {
        tenantSupport.requireTenant(em);
        String tndId = TenantContext.get();
        if (tndId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");

        if (req.nombre() == null || req.nombre().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        validarDimensiones(req);

        int cantidadMin = req.cantidadMin() != null ? req.cantidadMin() : 1;
        validarRango(cantidadMin, req.cantidadMax());
        validarSinSolape(null, cantidadMin, req.cantidadMax());

        TiendaEmpaque e = new TiendaEmpaque();
        e.setTndId(Long.parseLong(tndId));
        e.setNombre(req.nombre().trim());
        e.setLargoCm(req.largoCm());
        e.setAnchoCm(req.anchoCm());
        e.setAltoCm(req.altoCm());
        e.setPesoGramos(req.pesoGramos());
        e.setCantidadMin(cantidadMin);
        e.setCantidadMax(req.cantidadMax());
        e.setOrden(req.orden() != null ? req.orden() : (short) 0);
        e.setActivo(req.activo() == null || req.activo());
        return toResponse(repo.save(e));
    }

    @Transactional
    public EmpaqueResponse update(Long id, EmpaqueRequest req) {
        tenantSupport.requireTenant(em);
        TiendaEmpaque e = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empaque no encontrado"));

        if (req.nombre() != null && !req.nombre().isBlank()) e.setNombre(req.nombre().trim());
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
        if (req.pesoGramos() != null) {
            if (req.pesoGramos() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El peso no puede ser negativo");
            e.setPesoGramos(req.pesoGramos());
        }

        int nuevoMin = req.cantidadMin() != null ? req.cantidadMin() : e.getCantidadMin();
        Integer nuevoMax = req.cantidadMax() != null ? req.cantidadMax() : e.getCantidadMax();
        if (req.cantidadMin() != null || req.cantidadMax() != null) {
            validarRango(nuevoMin, nuevoMax);
            validarSinSolape(id, nuevoMin, nuevoMax);
            e.setCantidadMin(nuevoMin);
            e.setCantidadMax(nuevoMax);
        }

        if (req.orden() != null) e.setOrden(req.orden());
        if (req.activo() != null) e.setActivo(req.activo());

        return toResponse(repo.save(e));
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.requireTenant(em);
        TiendaEmpaque e = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empaque no encontrado"));
        repo.delete(e);
    }

    private void validarDimensiones(EmpaqueRequest req) {
        if (req.largoCm() == null || req.largoCm() <= 0
                || req.anchoCm() == null || req.anchoCm() <= 0
                || req.altoCm() == null || req.altoCm() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Largo, ancho y alto son obligatorios y deben ser mayores a 0");
        }
        if (req.pesoGramos() == null || req.pesoGramos() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El peso del empaque es obligatorio");
        }
    }

    private void validarRango(int cantidadMin, Integer cantidadMax) {
        if (cantidadMax != null && cantidadMax < cantidadMin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La cantidad máxima no puede ser menor a la mínima");
        }
    }

    /** Evita que dos empaques activos de la misma tienda cubran el mismo rango de cantidad de
     *  artículos — si se solaparan, PaqueteCalculoService no tendría forma determinística de
     *  elegir cuál usar. idExcluir es el propio registro cuando se está actualizando (no debe
     *  chocar consigo mismo). */
    private void validarSinSolape(Long idExcluir, int cantidadMin, Integer cantidadMax) {
        int maxEfectivo = cantidadMax != null ? cantidadMax : Integer.MAX_VALUE;
        for (TiendaEmpaque otro : repo.findAllByOrderByOrdenAscNombreAsc()) {
            if (!otro.isActivo()) continue;
            if (idExcluir != null && otro.getId().equals(idExcluir)) continue;
            int otroMaxEfectivo = otro.getCantidadMax() != null ? otro.getCantidadMax() : Integer.MAX_VALUE;
            boolean solapan = cantidadMin <= otroMaxEfectivo && otro.getCantidadMin() <= maxEfectivo;
            if (solapan) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "El rango de cantidad se solapa con el empaque \"" + otro.getNombre() + "\"");
            }
        }
    }

    private EmpaqueResponse toResponse(TiendaEmpaque e) {
        return new EmpaqueResponse(e.getId(), e.getNombre(), e.getLargoCm(), e.getAnchoCm(), e.getAltoCm(),
                e.getPesoGramos(), e.getCantidadMin(), e.getCantidadMax(), e.getOrden(), e.isActivo());
    }
}
