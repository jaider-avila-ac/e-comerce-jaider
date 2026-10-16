package jaider.ecommerce.auditoria;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {

    // El admin de una tienda no debe ver qué hizo el superadmin de la plataforma en SU tienda
    // (ej. activarla, configurar Resend/Cloudinary) — solo lo que hicieron el propio admin y su
    // equipo. "aud_admin_id" no es FK mapeada (admin_users es una tabla aparte, sin RLS), por eso
    // la exclusión es nativa en vez de un @ManyToOne.
    @Query(value = """
            SELECT a.* FROM auditoria_acciones a
            WHERE a.aud_tnd_id = :tndId
              AND NOT EXISTS (SELECT 1 FROM admin_users au WHERE au.id = a.aud_admin_id AND au.rol = 'superadmin')
            ORDER BY a.aud_creado_en DESC
            """,
            countQuery = """
            SELECT count(*) FROM auditoria_acciones a
            WHERE a.aud_tnd_id = :tndId
              AND NOT EXISTS (SELECT 1 FROM admin_users au WHERE au.id = a.aud_admin_id AND au.rol = 'superadmin')
            """,
            nativeQuery = true)
    Page<Auditoria> findVisibleByTndId(Long tndId, Pageable pageable);

    @Query(value = """
            SELECT a.* FROM auditoria_acciones a
            WHERE a.aud_tnd_id = :tndId AND a.aud_entidad = :entidad
              AND NOT EXISTS (SELECT 1 FROM admin_users au WHERE au.id = a.aud_admin_id AND au.rol = 'superadmin')
            ORDER BY a.aud_creado_en DESC
            """,
            countQuery = """
            SELECT count(*) FROM auditoria_acciones a
            WHERE a.aud_tnd_id = :tndId AND a.aud_entidad = :entidad
              AND NOT EXISTS (SELECT 1 FROM admin_users au WHERE au.id = a.aud_admin_id AND au.rol = 'superadmin')
            """,
            nativeQuery = true)
    Page<Auditoria> findVisibleByTndIdAndEntidad(Long tndId, String entidad, Pageable pageable);
}
