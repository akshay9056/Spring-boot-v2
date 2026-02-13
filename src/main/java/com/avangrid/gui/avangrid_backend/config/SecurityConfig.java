package com.avangrid.gui.avangrid_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.core.*;

import java.util.List;

/**
 * Security configuration for the application.
 * Configures OAuth2 resource server with JWT authentication,
 * security headers, and access control policies.
 */
@EnableWebSecurity
@EnableMethodSecurity
@Configuration
public class SecurityConfig {

    // Security header constants
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: https:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self'; " +
                    "frame-ancestors 'none';";

    private static final long HSTS_MAX_AGE_SECONDS = 31536000L; // 1 year

    // Endpoint constants
    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health",
            "/actuator/info",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/api/v1/search",
            "/api/v1/recording",
            "/api/v1/download",
            "/biz/avangrid-backend/v1/swagger-ui/**"
    };

    private static final String[] AUTHENTICATED_ENDPOINTS = {



            "/api/v1/metadata"
    };

    // JWT configuration properties
    private final String jwkSetUri;
    private final String issuerUri;
    private final String audience;

    public SecurityConfig(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.audience}") String audience) {
        this.jwkSetUri = jwkSetUri;
        this.issuerUri = issuerUri;
        this.audience = audience;
    }

    /**
     * Configures the security filter chain with comprehensive security settings.
     *
     * @param http HttpSecurity object to configure
     * @return Configured SecurityFilterChain
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
//                .requiresChannel(channel -> channel.anyRequest().requiresSecure())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .headers(headers -> headers
                        .defaultsDisabled()
                        .cacheControl(Customizer.withDefaults())
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(AUTHENTICATED_ENDPOINTS).authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler(new BearerTokenAccessDeniedHandler())
                );

        return http.build();
    }

    /**
     * Creates and configures a JWT decoder with comprehensive validation.
     * Validates issuer, timestamp, and audience claims.
     *
     * @return Configured JwtDecoder
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> timestampValidator = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> audienceValidator = createAudienceValidator();

        OAuth2TokenValidator<Jwt> compositeValidator = new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                timestampValidator,
                audienceValidator
        );

        decoder.setJwtValidator(compositeValidator);

        return decoder;
    }

    /**
     * Creates a custom audience validator for JWT tokens.
     *
     * @return OAuth2TokenValidator that validates the audience claim
     */
    private OAuth2TokenValidator<Jwt> createAudienceValidator() {
        return jwt -> {
            List<String> audiences = jwt.getAudience();

            if (audiences == null || audiences.isEmpty()) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Token must contain audience claim", null)
                );
            }

            if (audiences.contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }

            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token",
                            String.format("Token audience '%s' does not match required audience '%s'",
                                    audiences, audience),
                            null)
            );
        };
    }
}
