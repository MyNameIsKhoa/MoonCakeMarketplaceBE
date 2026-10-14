package mss.mooncake.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalServiceTokenFilter internalServiceTokenFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          InternalServiceTokenFilter internalServiceTokenFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.internalServiceTokenFilter = internalServiceTokenFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/internal/v1/users/**").hasAnyAuthority("ROLE_INTERNAL_COMMERCE", "ROLE_INTERNAL_RFQ")
                        .requestMatchers("/internal/v1/addresses/**").hasAuthority("ROLE_INTERNAL_COMMERCE")
                        .requestMatchers("/internal/v1/suppliers/**").hasAnyAuthority("ROLE_INTERNAL_CATALOG", "ROLE_INTERNAL_COMMERCE", "ROLE_INTERNAL_RFQ")
                        .requestMatchers("/internal/**").denyAll()
                        .anyRequest().hasAnyAuthority("ROLE_CUSTOMER", "ROLE_SUPPLIER", "ROLE_ADMIN")
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(internalServiceTokenFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> disableContainerJwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<InternalServiceTokenFilter> disableContainerServiceFilterRegistration(
            InternalServiceTokenFilter filter) {
        FilterRegistrationBean<InternalServiceTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
