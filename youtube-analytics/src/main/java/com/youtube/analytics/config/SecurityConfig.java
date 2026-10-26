package com.youtube.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.youtube.analytics.controller.OAuthController;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${app.ui.base-url:http://localhost:3000}") String uiBaseUrl,
            OAuth2AuthorizedClientRepository authorizedClientRepository) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/",
                        "/login/**",
                        "/error",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/api/youtube-analytics/v1/oauth/me",
                        "/api/youtube-analytics/v1/oauth/success",
                        "/api/youtube-analytics/v1/oauth/start"
                ).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/youtube-analytics/v1/auth/youtube/connect").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/youtube-analytics/v1/auth/status").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .authorizedClientRepository(authorizedClientRepository)
                .successHandler((request, response, authentication) -> {
                var session = request.getSession(false);
                if (session != null && Boolean.TRUE.equals(
                        session.getAttribute(OAuthController.RETURN_TO_UI_SESSION_ATTRIBUTE))) {
                    session.removeAttribute(OAuthController.RETURN_TO_UI_SESSION_ATTRIBUTE);
                    response.sendRedirect(uiBaseUrl);
                } else {
                    // Direct visits to /oauth2/authorization/google end on a backend JSON response,
                    // never on the frontend shell.
                    response.sendRedirect(request.getContextPath() + "/api/youtube-analytics/v1/oauth/success");
                }
            }))
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Authorization", "Content-Type", "Origin", "X-Requested-With"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
