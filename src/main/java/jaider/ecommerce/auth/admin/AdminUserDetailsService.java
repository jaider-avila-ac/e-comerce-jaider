package jaider.ecommerce.auth.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserDetailsService implements UserDetailsService {

    private final EmpleadoRepository repo;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Empleado emp = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Empleado no encontrado: " + email));

        return User.builder()
                .username(emp.getEmail())
                .password(emp.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + emp.getRol().toUpperCase())))
                .disabled(!emp.isActivo())
                .build();
    }
}
