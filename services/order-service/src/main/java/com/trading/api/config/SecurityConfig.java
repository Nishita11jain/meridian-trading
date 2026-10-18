package com.trading.api.config;

import com.trading.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;


@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String sharedSecret;
    private final String issuer;
    private final ObjectMapper objectMapper;
    private final List<String> corsAllowedOrigins;

    // The OpenAPI document is served by the running service (Sprint 8 requirement), so the docs are public
    // by default. Set app.docs.public=false for a deployed profile to put them behind the same token as
    // every other route.
    @Value("${app.docs.public:true}")
    private boolean docsPublic;

    public SecurityConfig(
            @Value("${app.security.jwt-secret}") String sharedSecret,
            @Value("${app.security.jwt-issuer:auth-service}") String issuer,
            @Value("${app.cors.allowed-origins:}") List<String> corsAllowedOrigins,
            ObjectMapper objectMapper) {
        this.sharedSecret = sharedSecret;
        this.issuer = issuer;
        this.objectMapper = objectMapper;
        this.corsAllowedOrigins = corsAllowedOrigins.stream().filter(o -> !o.isBlank()).toList();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // portfolio-api.yaml declares GET /health as public (security: []).
                .requestMatchers(HttpMethod.GET, "/health").permitAll()
                .requestMatchers(
                        "/swagger-ui.html",
                        "/v3/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**")
                .access((authentication, context) ->
                        new org.springframework.security.authorization.AuthorizationDecision(
                                docsPublic || authentication.get().isAuthenticated()
                                        && !(authentication.get() instanceof
                                        org.springframework.security.authentication.AnonymousAuthenticationToken)))
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder()))
                .authenticationEntryPoint(this::onAuthFailure)
                .accessDeniedHandler((req, res, ex) -> onAuthFailure(req, res, null)));
        return http.build();
    }

    // The allowed origins come from app.cors.allowed-origins (CORS_ALLOWED_ORIGINS in
    // .env), normally just the frontend's own origin. In the normal dev flow the browser never calls this service
    // directly anyway (ng serve's proxy.conf.js forwards /api same-origin),
    // so this only matters for a client that bypasses that proxy. Authorization
    // is a plain header, not a cookie, so it is unaffected by the credentials
    // flag CORS gates.
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(corsAllowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // Every kind of auth failure -- missing header, wrong scheme, expired,
    // forged signature -- funnels through here, producing the identical
    // AUTH-401 body every time. Deliberately: a more specific message
    // would tell an attacker which of the four they got wrong.
    private void onAuthFailure(jakarta.servlet.http.HttpServletRequest req, HttpServletResponse res,
                                org.springframework.security.core.AuthenticationException ex) throws java.io.IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse("AUTH-401", "Unauthorised")));
    }

        @Bean
    public JwtDecoder jwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Signature is checked by the decoder itself. createDefaultWithIssuer adds the issuer on top
        // of the expiry / not-before checks, as contracts/api-schemas/auth-api.yaml requires: a token whose `iss`
        // is missing or is not the configured issuer is refused, however well it is signed.
        //
        // The account a token may trade is its `accountId` claim (contracts/api-schemas/auth-api.yaml), and the
        // controllers read nothing else. A token without a positive whole-number accountId is
        // malformed, so it is refused here with the same uniform AUTH-401 as every other bad token,
        // instead of reaching a controller and failing there with a 500.
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> accountId = new JwtClaimValidator<Object>("accountId", SecurityConfig::isPositiveWholeNumber);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, accountId));
        return decoder;
    }

    private static boolean isPositiveWholeNumber(Object claim) {
        if (!(claim instanceof Number number)) {
            return false;
        }
        double value = number.doubleValue();
        return value > 0 && value == Math.rint(value) && value <= Long.MAX_VALUE;
    }
}