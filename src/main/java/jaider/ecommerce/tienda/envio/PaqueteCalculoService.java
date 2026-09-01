package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.catalogo.producto.Producto;
import jaider.ecommerce.catalogo.producto.ProductoRepository;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Calcula el "paquete" de un carrito — PLAN_INTEGRACION_ENVIA.md, Fase 1: peso total (suma de
 * las especificaciones logísticas de cada producto × cantidad, más el peso del empaque) y qué
 * empaque de {@code tienda_empaques} cubre la cantidad total de artículos. Este servicio NO se
 * llama todavía desde ningún checkout real (eso es la Fase 3) — es la pieza de cálculo, lista
 * para que esa fase la use.
 *
 * Producto ≠ paquete: nunca se suman las dimensiones de cada producto — el paquete final usa
 * SIEMPRE las dimensiones del empaque elegido, el peso sí es la suma real de los productos.
 */
@Service
@RequiredArgsConstructor
public class PaqueteCalculoService {

    private final ProductoRepository productoRepo;
    private final EspecificacionLogisticaRepository especificacionRepo;
    private final TiendaEmpaqueRepository empaqueRepo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public PaqueteCalculado calcular(List<ItemParaPaquete> items) {
        tenantSupport.requireTenant(em);

        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El carrito está vacío");
        }

        int pesoProductosGramos = 0;
        int totalArticulos = 0;
        for (ItemParaPaquete item : items) {
            Producto producto = productoRepo.findById(item.productoId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Producto no encontrado: " + item.productoId()));
            if (producto.getEspecificacionId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El producto \"" + producto.getNombre() + "\" no tiene una especificación logística "
                                + "asignada — no se puede calcular el envío hasta que se le asigne una");
            }
            EspecificacionLogistica esp = especificacionRepo.findById(producto.getEspecificacionId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "La especificación logística del producto \"" + producto.getNombre() + "\" ya no existe"));

            pesoProductosGramos += esp.getPesoGramos() * item.cantidad();
            totalArticulos += item.cantidad();
        }

        TiendaEmpaque empaque = elegirEmpaque(totalArticulos);
        int pesoTotalGramos = pesoProductosGramos + empaque.getPesoGramos();

        return new PaqueteCalculado(pesoTotalGramos, empaque.getId(), empaque.getNombre(),
                empaque.getLargoCm(), empaque.getAnchoCm(), empaque.getAltoCm());
    }

    private TiendaEmpaque elegirEmpaque(int totalArticulos) {
        return empaqueRepo.findByActivoTrueOrderByCantidadMinAsc().stream()
                .filter(e -> totalArticulos >= e.getCantidadMin()
                        && (e.getCantidadMax() == null || totalArticulos <= e.getCantidadMax()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No hay un empaque configurado que cubra " + totalArticulos + " artículo(s) — "
                                + "agrega uno en la configuración de empaques"));
    }
}
