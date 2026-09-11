package com.example.autoskaner_ai.auth;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    /**
     * The three endpoints a caller reaches before it has a token. Listed by name rather than as
     * {@code /api/auth/**} because {@code GET /api/auth/me} lives under the same prefix and reads
     * the principal — a wildcard here is how that endpoint becomes public.
     */
    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
        "/api/auth/register", "/api/auth/login", "/api/auth/refresh"
    };

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                // No cookies and no sessions, so there is no cross-site request forgery surface: an
                // attacker's page cannot make the browser attach a bearer token it never stored.
                // Leaving CSRF on would 403 every POST from the SPA instead.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // Preflight carries no Authorization header by
                                        // specification, so requiring one here fails the request
                                        // before the real call is ever made.
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .requestMatchers(PUBLIC_AUTH_ENDPOINTS)
                                        .permitAll()
                                        .requestMatchers("/actuator/health")
                                        .permitAll()
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        // Everything outside /api/** keeps the answer it gave
                                        // before this change. Render probes `/`, and turning that
                                        // into a 401 would make the platform's own health signal
                                        // depend on the auth configuration.
                                        .anyRequest()
                                        .permitAll())
                .oauth2ResourceServer(
                        resourceServer ->
                                resourceServer
                                        // Set in BOTH places on purpose. A bearer-token failure is
                                        // handled by the resource server's own entry point, which
                                        // overrides the one below; the one below covers a request
                                        // that carried no token at all. Configuring only
                                        // exceptionHandling leaves a malformed token answering
                                        // Spring's WWW-Authenticate body instead of ErrorResponse.
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler)
                                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /**
     * Stores {@code {bcrypt}$2a$10$…}: the algorithm is recorded inside the hash, so a future move
     * to argon2 needs no schema change and no flag day. The {@code users} table holds no rows from
     * before this change, so there is no unprefixed legacy hash to keep working.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * This replaces the former {@code common.CorsConfig}, a {@code WebMvcConfigurer}, and the move
     * is the point rather than a tidy-up — the roadmap flagged it as F-03's first risk. A
     * {@code WebMvcConfigurer}'s CORS mapping is applied by the MVC handler, which runs after the
     * security filter chain; a preflight {@code OPTIONS} would therefore be judged by security
     * before the mapping was ever consulted, and the browser would report an opaque CORS failure
     * for what is really an authorization decision.
     *
     * <p>{@code allowCredentials} stays false: the session is a bearer header, not a cookie, which
     * is what lets the two different sites talk to each other at all.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${frontend.url:https://autoskaner-ai.pages.dev}") String frontendUrl) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:4200", frontendUrl));
        // PATCH was missing from the WebMvcConfigurer this replaces; S-03 renames a saved analysis
        // with it, and a preflight for a method absent from this list is refused.
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
