package jaider.ecommerce.cupon;

import jaider.ecommerce.auditoria.AuditoriaService;
import jaider.ecommerce.auth.admin.AdminUser;
import jaider.ecommerce.auth.admin.AdminUserRepository;
import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cupones de descuento ("bono de compra") — PENDIENTE_DUO_CHIC. Los gestiona el propio admin de
 *  cada tienda, igual que Wompi (ver TiendaWompiCredencialesService): es una herramienta de
 *  mercadeo de la tienda, no algo que dependa del superadmin de la plataforma. */
@Service
@RequiredArgsConstructor
public class CuponService {

    private static final Set<String> TIPOS_VALIDOS = Set.of("porcentaje", "fijo");
    private static final String ALFABETO_CODIGO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sin 0/O/1/I, se confunden
    private static final int LARGO_CODIGO_ALEATORIO = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CuponRepository repository;
    private final AuditoriaService auditoriaService;
    private final TenantSupport tenantSupport;
    private final AdminUserRepository adminUserRepository;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<CuponResponse> listar(Long tndId) {
        tenantSupport.requireTenant(em);
        return repository.findByTndIdOrderByCreadoEnDesc(tndId).stream().map(CuponResponse::de).toList();
    }

    @Transactional
    public CuponResponse crear(Long tndId, CrearCuponRequest req, String adminEmail) {
        tenantSupport.requireTenant(em);
        Long adminId = adminIdDe(adminEmail);

        String tipo = req.tipoDescuento() == null ? "" : req.tipoDescuento().trim().toLowerCase();
        if (!TIPOS_VALIDOS.contains(tipo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tipoDescuento debe ser \"porcentaje\" o \"fijo\"");
        }
        if (req.valor() == null || req.valor() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El valor del descuento debe ser mayor a 0");
        }
        if ("porcentaje".equals(tipo) && req.valor() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un descuento por porcentaje no puede superar 100");
        }
        int maxUsos = req.maxUsos() == null ? 1 : req.maxUsos();
        if (maxUsos <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El límite de usos debe ser mayor a 0");
        }

        String codigo = req.generarAleatorio() ? generarCodigoUnico(tndId) : normalizarCodigo(req.codigo());
        if (codigo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe un código o marca \"generar aleatorio\"");
        }
        if (!req.generarAleatorio() && repository.findByTndIdAndCodigo(tndId, codigo).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un cupón con el código \"" + codigo + "\"");
        }

        Cupon c = new Cupon();
        c.setTndId(tndId);
        c.setCodigo(codigo);
        c.setTipoDescuento(tipo);
        c.setValor(req.valor());
        c.setMaxUsos(maxUsos);
        c.setActivo(true);
        c.setCreadoPor(adminId);
        Cupon guardado = repository.save(c);

        auditoriaService.registrar(tndId, adminId, "cupon.creado", "cupon", guardado.getId(),
                Map.of("codigo", codigo, "tipo_descuento", tipo, "valor", req.valor(), "max_usos", maxUsos));

        return CuponResponse.de(guardado);
    }

    @Transactional
    public CuponResponse cambiarActivo(Long tndId, Long id, boolean activo, String adminEmail) {
        tenantSupport.requireTenant(em);
        Long adminId = adminIdDe(adminEmail);
        Cupon c = cuponDeLaTienda(tndId, id);
        c.setActivo(activo);
        auditoriaService.registrar(tndId, adminId, activo ? "cupon.activado" : "cupon.desactivado",
                "cupon", id, Map.of("codigo", c.getCodigo()));
        return CuponResponse.de(c);
    }

    /** Preview SIN consumir un uso — el carrito la llama al escribir el código, antes de pagar.
     *  El canje real (que sí descuenta un uso) solo pasa en el checkout, ver canjear(). */
    @Transactional(readOnly = true)
    public ValidarCuponResponse validar(Long tndId, String codigoCrudo, long subtotalCentavos) {
        tenantSupport.requireTenant(em);
        String codigo = normalizarCodigo(codigoCrudo);
        if (codigo.isBlank()) {
            return new ValidarCuponResponse(false, "Escribe un código", null, null, null, null);
        }
        var opt = repository.findByTndIdAndCodigo(tndId, codigo);
        if (opt.isEmpty()) {
            return new ValidarCuponResponse(false, "Ese código no existe", codigo, null, null, null);
        }
        Cupon c = opt.get();
        if (!c.isActivo()) {
            return new ValidarCuponResponse(false, "Ese cupón ya no está activo", codigo, null, null, null);
        }
        if (c.getUsosActuales() >= c.getMaxUsos()) {
            return new ValidarCuponResponse(false, "Ese cupón ya alcanzó su límite de usos", codigo, null, null, null);
        }
        long descuento = calcularDescuento(c, subtotalCentavos);
        return new ValidarCuponResponse(true, null, c.getCodigo(), c.getTipoDescuento(), c.getValor(), descuento);
    }

    /** Canje real — se llama UNA vez, al confirmar el checkout (ver PedidoCheckoutService). El
     *  UPDATE condicionado de canjear() es quien de verdad decide si todavía había usos
     *  disponibles; esta validación de arriba (validar()) es solo para que el carrito avise antes
     *  de intentar pagar, nunca la fuente de verdad. */
    @Transactional
    public Cupon canjear(Long tndId, String codigoCrudo) {
        tenantSupport.requireTenant(em);
        String codigo = normalizarCodigo(codigoCrudo);
        Cupon c = repository.findByTndIdAndCodigo(tndId, codigo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ese cupón no existe"));
        int filas = repository.canjear(c.getId());
        if (filas == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese cupón ya no está disponible (inactivo o sin usos)");
        }
        c.setUsosActuales(c.getUsosActuales() + 1); // refleja el canje que ya ocurrió en BD
        return c;
    }

    public long calcularDescuento(Cupon c, long subtotalCentavos) {
        long descuento = "porcentaje".equals(c.getTipoDescuento())
                ? Math.round(subtotalCentavos * (c.getValor() / 100.0))
                : c.getValor() * 100; // valor fijo viene en pesos; el pedido trabaja en centavos
        return Math.min(descuento, subtotalCentavos); // nunca más que el subtotal
    }

    /** Resuelve el id del admin DENTRO de esta misma transacción — a propósito, no en el
     *  controller: ahí el SET LOCAL app.current_tnd_id de tenantSupport.requireTenant() ya se
     *  perdió (expira con la transacción que lo puso), así que una consulta aparte sobre
     *  admin_users (con RLS forzado) nunca encontraba al admin ("Admin no encontrado" incluso con
     *  credenciales válidas — bug encontrado 2026-11-21 al probar los cupones en vivo, presente
     *  también en TiendaWompiController con el mismo patrón). */
    private Long adminIdDe(String email) {
        return adminUserRepository.findByEmail(email)
                .map(AdminUser::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin no encontrado"));
    }

    private Cupon cuponDeLaTienda(Long tndId, Long id) {
        Cupon c = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cupón no encontrado"));
        if (!tndId.equals(c.getTndId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cupón no encontrado");
        }
        return c;
    }

    private String normalizarCodigo(String codigo) {
        return codigo == null ? "" : codigo.trim().toUpperCase().replaceAll("\\s+", "");
    }

    private String generarCodigoUnico(Long tndId) {
        for (int intento = 0; intento < 10; intento++) {
            String candidato = generarCodigoAleatorio();
            if (repository.findByTndIdAndCodigo(tndId, candidato).isEmpty()) return candidato;
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar un código único, intenta de nuevo");
    }

    private String generarCodigoAleatorio() {
        StringBuilder sb = new StringBuilder(LARGO_CODIGO_ALEATORIO);
        for (int i = 0; i < LARGO_CODIGO_ALEATORIO; i++) {
            sb.append(ALFABETO_CODIGO.charAt(RANDOM.nextInt(ALFABETO_CODIGO.length())));
        }
        return sb.toString();
    }
}
