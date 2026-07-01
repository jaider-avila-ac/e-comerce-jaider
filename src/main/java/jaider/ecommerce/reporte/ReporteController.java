package jaider.ecommerce.reporte;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reportes")
@RequiredArgsConstructor
public class ReporteController {

    private final ReporteService service;

    @GetMapping("/resumen")
    public ReporteResumenResponse resumen(@RequestParam(required = false) String mes) {
        return service.resumen(mes);
    }

    @GetMapping("/pedidos-por-estado")
    public List<Map<String, Object>> pedidosPorEstado(@RequestParam(required = false) String mes) {
        return service.pedidosPorEstado(mes);
    }

    @GetMapping("/productos-mas-vendidos")
    public List<Map<String, Object>> productosMasVendidos(@RequestParam(required = false) String mes) {
        return service.productosMasVendidos(mes);
    }

    @GetMapping("/ventas-por-categoria")
    public List<Map<String, Object>> ventasPorCategoria(@RequestParam(required = false) String mes) {
        return service.ventasPorCategoria(mes);
    }
}
