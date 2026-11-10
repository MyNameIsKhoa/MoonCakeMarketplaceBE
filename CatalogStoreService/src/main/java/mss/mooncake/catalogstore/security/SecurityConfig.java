package mss.mooncake.catalogstore.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;

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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(catalogImageCorsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // 403 của Spring Security được forward sang /error; JWT filter không chạy lại ở lượt ERROR,
                        // nếu /error cần auth thì user đã đăng nhập bị trả 401 thay vì 403.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/uploads/catalog/**").permitAll()
                        // Public read: GET /api/stores và GET /api/stores/{id}
                        .requestMatchers(HttpMethod.GET, "/api/stores", "/api/stores/**").permitAll()
                        // Public read catalog (CA01, CA02, CA06, CA07, CA11, CA12, CA16, CA17).
                        // Service vẫn lọc DRAFT/INACTIVE: chỉ owner đăng nhập mới thấy.
                        .requestMatchers(HttpMethod.GET,
                                "/api/categories", "/api/categories/**",
                                "/api/products", "/api/products/**",
                                "/api/promotions", "/api/promotions/**")
                        .permitAll()
                        // Category dùng chung toàn sàn: chỉ admin được ghi (CA03–CA05)
                        .requestMatchers("/api/categories", "/api/categories/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/admin/catalog/**").hasAuthority("ROLE_ADMIN")
                        // Swagger
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/internal/v1/inventory/**").hasAuthority("ROLE_INTERNAL_COMMERCE")
                        .requestMatchers("/internal/v1/promotions/calculate").hasAuthority("ROLE_INTERNAL_COMMERCE")
                        .requestMatchers("/internal/**").hasAnyAuthority("ROLE_INTERNAL_COMMERCE", "ROLE_INTERNAL_RFQ")
                        // Ghi store/branch/product/variant/promotion: chỉ supplier đã VERIFIED (ROLE_SUPPLIER) hoặc admin.
                        // Role chốt lúc đăng nhập nên service vẫn kiểm tra VERIFIED + ownership qua Identity mỗi lần ghi.
                        .requestMatchers(HttpMethod.POST, "/api/stores", "/api/stores/**",
                                "/api/products", "/api/products/**", "/api/promotions", "/api/promotions/**")
                        .hasAnyAuthority("ROLE_SUPPLIER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/stores/**", "/api/products/**", "/api/promotions/**")
                        .hasAnyAuthority("ROLE_SUPPLIER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/stores/**", "/api/products/**", "/api/promotions/**")
                        .hasAnyAuthority("ROLE_SUPPLIER", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/stores/**", "/api/products/**", "/api/promotions/**")
                        .hasAnyAuthority("ROLE_SUPPLIER", "ROLE_ADMIN")
                        // Mọi request còn lại cần JWT
                        .anyRequest().hasAnyAuthority("ROLE_CUSTOMER", "ROLE_SUPPLIER", "ROLE_ADMIN"))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(internalServiceTokenFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    CorsConfigurationSource catalogImageCorsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Accept", "Origin"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/uploads/catalog/**", cors);
        return source;
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> disableContainerJwtFilterRegistration(
            JwtAuthenticationFilter filter) {
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
