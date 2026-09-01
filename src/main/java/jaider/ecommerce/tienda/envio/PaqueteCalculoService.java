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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arma el arreglo {@code packages[]} que se le manda a Envia.com para cotizar un carrito —
 * PLAN_INTEGRACION_ENVIA.md, Fase 1. Un producto no tiene peso/dimensiones propias, se le
 * asigna un {@link TiendaEmpaque} directo — este servicio agrupa el carrito por empaque
 * (sumando cantidades) y arma UN renglón por cada empaque distinto, con SU peso/dimensiones,
 * dejando que la propia API de Envia sume todo al cotizar (docs.envia.com/docs/shipping-
 * multiple-packages: "el sistema suma automáticamente pesos y dimensiones de todos los
 * paquetes"). Este servicio NO combina/suma nada por su cuenta — solo agrupa.
 *
 * Todavía no se llama desde ningún checkout real (eso es la Fase 3) — es la pieza de cálculo,
 * lista para que esa fase la use.
 */
@Service
@RequiredArgsConstructor
public class PaqueteCalculoService {

    private final ProductoRepository productoRepo;
    private final TiendaEmpaqueRepository empaqueRepo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<PaqueteCalculado> calcular(List<ItemParaPaquete> items) {
        tenantSupport.requireTenant(em);

        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El carrito está vacío");
        }

        // LinkedHashMap: conserva el orden en que aparece cada empaque por primera vez —
        // resultado determinístico, no depende del orden de iteración de un HashMap normal.
        Map<Long, Integer> cantidadPorEmpaque = new LinkedHashMap<>();
        for (ItemParaPaquete item : items) {
            Producto producto = productoRepo.findById(item.productoId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Producto no encontrado: " + item.productoId()));
            if (producto.getEmpaqueId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El producto \"" + producto.getNombre() + "\" no tiene un empaque asignado — "
                                + "no se puede calcular el envío hasta que se le asigne uno");
            }
            cantidadPorEmpaque.merge(producto.getEmpaqueId(), item.cantidad(), Integer::sum);
        }

        return cantidadPorEmpaque.entrySet().stream()
                .map(entry -> {
                    TiendaEmpaque empaque = empaqueRepo.findById(entry.getKey())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "El empaque asignado a un producto ya no existe"));
                    return new PaqueteCalculado(empaque.getId(), empaque.getNombre(), entry.getValue(),
                            empaque.getPesoGramos(), empaque.getLargoCm(), empaque.getAnchoCm(), empaque.getAltoCm());
                })
                .toList();
    }
}
