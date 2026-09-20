package jaider.ecommerce.config;

import jaider.ecommerce.auth.admin.AdminUserDetailsService;
import jaider.ecommerce.auth.jwt.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AdminUserDetailsService adminUserDetailsService;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(adminUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authenticationProvider(authProvider())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/api/v1/health/**",
                    "/api/v1/auth/admin/login",
                    "/api/v1/public/**",
                    // Operación GLOBAL explícitamente separada del resto (§3.3 del plan
                    // multi-tenant: "las operaciones globales deben estar separadas y marcadas
                    // explícitamente como tales") — no pertenece a ningún tenant existente, así
                    // que no puede protegerse con el JWT normal de un admin de tienda. Su propia
                    // autorización (una llave compartida) vive en TenantProvisioningController,
                    // no acá.
                    "/api/v1/aprovisionamiento/**",
                    "/ws/**",
                    "/error"
                ).permitAll()
                // El superadmin NO opera sobre datos de ninguna tienda (decisión explícita del
                // usuario, 2026-08-30) — solo puede tocar /api/v1/superadmin/** (totales
                // agregados de la plataforma, ver SuperadminController) y su propia cuenta
                // (me/logout). Para actuar DENTRO de una tienda hace falta el admin propio de
                // esa tienda, nunca esta cuenta — por eso SUPERADMIN queda fuera de la regla
                // general de /api/v1/** de abajo.
                .requestMatchers("/api/v1/auth/admin/me", "/api/v1/auth/admin/logout")
                    .hasAnyRole("SUPERADMIN", "ADMIN", "COLABORADOR", "BODEGA")
                .requestMatchers("/api/v1/superadmin/**").hasRole("SUPERADMIN")
                .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "COLABORADOR", "BODEGA")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
