package mss.mooncake.commerce.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtFilter;
    private final InternalServiceTokenFilter internalServiceTokenFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, InternalServiceTokenFilter internalServiceTokenFilter) {
        this.jwtFilter = jwtFilter;
        this.internalServiceTokenFilter = internalServiceTokenFilter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/internal/**").hasAuthority("ROLE_INTERNAL_RFQ")
                        .anyRequest().hasAnyAuthority("ROLE_CUSTOMER", "ROLE_SUPPLIER", "ROLE_ADMIN"))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
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
