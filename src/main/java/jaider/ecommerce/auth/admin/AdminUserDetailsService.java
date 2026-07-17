package jaider.ecommerce.auth.admin;

import jaider.ecommerce.shared.TenantSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository repo;
    private final TenantSupport tenantSupport;

    @PersistenceContext
    private EntityManager em;

    // REQUIRES_NEW: AuthController.login() ya está en su propia @Transactional. Si este método
    // se uniera a esa transacción y lanzara UsernameNotFoundException (email no encontrado), la
    // marcaría como rollback-only — DaoAuthenticationProvider igual la convierte en
    // BadCredentialsException y login() la maneja, pero el commit posterior fallaría con
    // UnexpectedRollbackException (500) en vez de devolver el 401 esperado. Aislada en su propia
    // transacción, un fallo acá nunca contamina la transacción del llamador.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // admin_users tiene RLS por tienda_id; sin esto, un admin/colaborador
        // (no superadmin) sería invisible para la consulta y el login fallaría.
        tenantSupport.applyTenant(em);

        AdminUser admin = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Administrador no encontrado: " + email));

        return User.builder()
                .username(admin.getEmail())
                .password(admin.getPassword())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + admin.getRol().toUpperCase())))
                .disabled(!admin.isActivo())
                .build();
    }
}
