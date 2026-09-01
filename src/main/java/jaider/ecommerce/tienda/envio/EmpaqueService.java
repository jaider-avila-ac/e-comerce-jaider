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
 * CRUD del catálogo de empaques de la tienda actual (PLAN_INTEGRACION_ENVIA.md, Fase 1). Un
 * empaque junta peso + dimensiones — un producto se asigna DIRECTO a uno
 * ({@link jaider.ecommerce.catalogo.producto.Producto#getEmpaqueId()}), no hay una tabla de
 * peso aparte (ver {@link TiendaEmpaque}). Mismo patrón de aislamiento por tenant que el resto
 * de servicios de catálogo: {@link TenantSupport#requireTenant} + RLS de Postgres.
 */
@Service
@RequiredArgsConstructor
public class EmpaqueService {

    /** Coordinadora publica 50cm como arista máxima real — ver CHECK de la BD (chk_tep_medida_maxima). */
    private static final short MEDIDA_MAXIMA_CM = 50;

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
        validarDimensiones(req.largoCm(), req.anchoCm(), req.altoCm());
        if (req.pesoGramos() == null || req.pesoGramos() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El peso del empaque es obligatorio");
        }

        TiendaEmpaque e = new TiendaEmpaque();
        e.setTndId(Long.parseLong(tndId));
        e.setNombre(req.nombre().trim());
        e.setLargoCm(req.largoCm());
        e.setAnchoCm(req.anchoCm());
        e.setAltoCm(req.altoCm());
        e.setPesoGramos(req.pesoGramos());
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
        if (req.largoCm() != null || req.anchoCm() != null || req.altoCm() != null) {
            short largo = req.largoCm() != null ? req.largoCm() : e.getLargoCm();
            short ancho = req.anchoCm() != null ? req.anchoCm() : e.getAnchoCm();
            short alto = req.altoCm() != null ? req.altoCm() : e.getAltoCm();
            validarDimensiones(largo, ancho, alto);
            e.setLargoCm(largo);
            e.setAnchoCm(ancho);
            e.setAltoCm(alto);
        }
        if (req.pesoGramos() != null) {
            if (req.pesoGramos() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El peso no puede ser negativo");
            e.setPesoGramos(req.pesoGramos());
        }
        if (req.orden() != null) e.setOrden(req.orden());
        // Corrección de auditoría (2026-09-01, tercera vuelta): desactivar un empaque que
        // productos ACTIVOS siguen usando, en una tienda ya en modo 'envia', rompería el checkout
        // para el primer cliente que compre uno de esos productos (mismo riesgo que activar
        // 'envia' sin validar — ver TiendaConfigService.validarListaParaEnvia — pero acá ocurría
        // DESPUÉS de la activación, sin ninguna revalidación).
        if (Boolean.FALSE.equals(req.activo()) && productosActivosUsando(id) > 0 && tiendaEnModoEnvia()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede desactivar: " + productosActivosUsando(id) + " producto(s) activo(s) todavía lo usan y esta tienda calcula el envío real");
        }
        if (req.activo() != null) e.setActivo(req.activo());

        return toResponse(repo.save(e));
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.requireTenant(em);
        TiendaEmpaque e = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empaque no encontrado"));
        // Corrección de auditoría (2026-09-01, tercera vuelta): antes, ON DELETE SET NULL dejaba
        // en silencio a los productos que lo usaban sin ningún empaque — en una tienda ya en modo
        // 'envia', eso rompe el checkout para el primer cliente que compre uno de esos productos.
        long productosAfectados = productosActivosUsando(id);
        if (productosAfectados > 0 && tiendaEnModoEnvia()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede eliminar: " + productosAfectados + " producto(s) activo(s) todavía lo usan y esta tienda calcula el envío real — reasígnalos a otro empaque primero");
        }
        repo.delete(e);
    }

    private long productosActivosUsando(Long empaqueId) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM productos WHERE prd_activo = true AND prd_empaque_id = :empaqueId")
                .setParameter("empaqueId", empaqueId)
                .getSingleResult()).longValue();
    }

    private boolean tiendaEnModoEnvia() {
        String tndId = TenantContext.get();
        if (tndId == null) return false;
        Object modo = em.createNativeQuery("SELECT tnd_envio_modo FROM tiendas WHERE tnd_id = :tndId")
                .setParameter("tndId", Long.parseLong(tndId))
                .getSingleResult();
        return "envia".equals(modo);
    }

    private void validarDimensiones(Short largoCm, Short anchoCm, Short altoCm) {
        if (largoCm == null || largoCm <= 0 || anchoCm == null || anchoCm <= 0 || altoCm == null || altoCm <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Largo, ancho y alto son obligatorios y deben ser mayores a 0");
        }
        if (largoCm > MEDIDA_MAXIMA_CM || anchoCm > MEDIDA_MAXIMA_CM || altoCm > MEDIDA_MAXIMA_CM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ningún lado puede superar " + MEDIDA_MAXIMA_CM + "cm (límite real de las transportadoras)");
        }
    }

    private EmpaqueResponse toResponse(TiendaEmpaque e) {
        return new EmpaqueResponse(e.getId(), e.getNombre(), e.getLargoCm(), e.getAnchoCm(), e.getAltoCm(),
                e.getPesoGramos(), e.getOrden(), e.isActivo());
    }
}
