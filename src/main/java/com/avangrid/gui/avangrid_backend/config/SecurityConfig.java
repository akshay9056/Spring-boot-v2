package com.avangrid.gui.avangrid_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // Disable CSRF because we’re using stateless JWT-based authentication
                .csrf(AbstractHttpConfigurer::disable)

                // Configure endpoint access rules
                .authorizeHttpRequests(auth -> auth
                        // ✅ Public endpoints — no authentication required
                        .requestMatchers(
                                "/actuator/**",         // health, metrics
                                "/swagger-ui/**",       // swagger UI
                                "/v3/api-docs/**",      // openapi docs
                                "/recordings"           // your public endpoint
                        ).permitAll()

                        // 🔐 Secure endpoints — require valid JWT token
                        .requestMatchers(
                                "/fetch-metadata",
                                "/recording-metadata",
                                "/recording",
                                "/download-recordings"
                        ).authenticated()

                        // Everything else denied by default
                        .anyRequest().denyAll()
                )

                // Configure JWT validation using Azure AD
                .oauth2ResourceServer(oauth2 -> oauth2.jwt());

        return http.build();
    }
}
