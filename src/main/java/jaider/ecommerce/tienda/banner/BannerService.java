package jaider.ecommerce.tienda.banner;

import jaider.ecommerce.infra.CloudinaryService;
import jaider.ecommerce.notificacion.event.OfertaEvent;
import jaider.ecommerce.shared.TenantSupport;
import jaider.ecommerce.shared.interceptor.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BannerService {

    private final BannerRepository repo;
    private final TenantSupport tenantSupport;
    private final ApplicationEventPublisher eventPublisher;
    private final CloudinaryService cloudinaryService;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<BannerResponse> getAll() {
        tenantSupport.applyTenant(em);
        return repo.findAllByOrderByPosicionAscOrdenAscIdAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<BannerResponse> getActivosByPosicion(String posicion) {
        tenantSupport.applyTenant(em);
        return repo.findByPosicionActivos(posicion).stream().map(this::toResponse).toList();
    }

    @Transactional
    public BannerResponse create(BannerRequest req) {
        tenantSupport.applyTenant(em);
        String tndId = TenantContext.get();
        if (tndId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin contexto de tenant");
        if (req.url() == null || req.url().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La URL del banner es obligatoria");
        validateLink(req.ctaLink());

        String posicion = (req.posicion() != null && !req.posicion().isBlank()) ? req.posicion() : "hero";
        String tipo     = (req.tipo()     != null && !req.tipo().isBlank())     ? req.tipo()     : "imagen";

        // Auto-assign orden: siempre al final de los banners existentes
        Number maxOrden = (Number) em.createNativeQuery(
                "SELECT COALESCE(MAX(ban_orden), -1) FROM banners").getSingleResult();
        short nuevoOrden = (short) (maxOrden.intValue() + 1);

        boolean activo = req.activo() == null || req.activo();

        Number id = (Number) em.createNativeQuery(
                "INSERT INTO banners (ban_tnd_id, ban_posicion, ban_tipo, ban_url, ban_titulo, ban_cta_link, ban_orden, ban_activo) " +
                "VALUES (:tndId, CAST(:posicion AS posicion_banner), CAST(:tipo AS tipo_media), :url, :titulo, :ctaLink, :orden, :activo) " +
                "RETURNING ban_id")
                .setParameter("tndId",    Long.parseLong(tndId))
                .setParameter("posicion", posicion)
                .setParameter("tipo",     tipo)
                .setParameter("url",      req.url())
                .setParameter("titulo",   req.titulo())
                .setParameter("ctaLink",  req.ctaLink())
                .setParameter("orden",    nuevoOrden)
                .setParameter("activo",   activo)
                .getSingleResult();

        if ("promo".equals(posicion) && activo) {
            publicarOferta(Long.parseLong(tndId), id.longValue(), req.titulo());
        }

        em.clear();
        return toResponse(repo.findById(id.longValue())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al crear el banner")));
    }

    @Transactional
    public BannerResponse update(Long id, BannerRequest req) {
        tenantSupport.applyTenant(em);
        Banner b = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Banner no encontrado: " + id));

        boolean eraActivo = b.isActivo();
        String urlAnterior = b.getUrl();

        validateLink(req.ctaLink());
        if (req.url()    != null && !req.url().isBlank()) b.setUrl(req.url());
        if (req.titulo() != null) b.setTitulo(req.titulo());
        if (req.ctaLink()!= null) b.setCtaLink(req.ctaLink());
        if (req.orden()  != null) b.setOrden(req.orden());
        if (req.activo() != null) b.setActivo(req.activo());
        repo.save(b);

        String posicionFinal = req.posicion() != null && !req.posicion().isBlank() ? req.posicion() : b.getPosicion();
        if (req.posicion() != null || req.tipo() != null) {
            repo.updatePosicionTipo(
                    id,
                    posicionFinal,
                    req.tipo() != null && !req.tipo().isBlank() ? req.tipo() : b.getTipo()
            );
        }

        // Se considera "publicada" la transición a activo=true de un banner tipo "promo"
        // (ya sea porque se acaba de activar, o porque se cambia su posición a "promo" ya activo).
        if ("promo".equals(posicionFinal) && !eraActivo && b.isActivo()) {
            publicarOferta(b.getTndId(), id, b.getTitulo());
        }

        em.clear();
        BannerResponse resp = toResponse(repo.findById(id).orElseThrow());

        if (req.url() != null && !req.url().isBlank() && !req.url().equals(urlAnterior)) {
            cloudinaryService.delete(urlAnterior);
        }
        return resp;
    }

    private void publicarOferta(Long tndId, Long banId, String titulo) {
        String tituloNotif = "Oferta especial";
        String mensaje = (titulo != null && !titulo.isBlank())
                ? titulo
                : "Hay una nueva promoción disponible en la tienda.";
        eventPublisher.publishEvent(new OfertaEvent(tndId, tituloNotif, mensaje, "banner", banId));
    }

    @Transactional
    public void reordenar(List<Long> ids) {
        tenantSupport.applyTenant(em);
        for (int i = 0; i < ids.size(); i++) {
            em.createNativeQuery("UPDATE banners SET ban_orden = :orden WHERE ban_id = :id")
                    .setParameter("orden", (short) i)
                    .setParameter("id",    ids.get(i))
                    .executeUpdate();
        }
    }

    @Transactional
    public void delete(Long id) {
        tenantSupport.applyTenant(em);
        Banner b = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Banner no encontrado: " + id));
        repo.deleteById(id);
        cloudinaryService.delete(b.getUrl());
    }

    private static void validateLink(String link) {
        if (link == null || link.isBlank()) return;
        String lower = link.strip().toLowerCase();
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Protocolo de enlace no permitido");
        }
    }

    private BannerResponse toResponse(Banner b) {
        return new BannerResponse(
                b.getId(), b.getPosicion(), b.getTipo(), b.getUrl(),
                b.getTitulo(), b.getCtaLink(), b.getOrden(), b.isActivo(), b.getCreadoEn()
        );
    }
}
