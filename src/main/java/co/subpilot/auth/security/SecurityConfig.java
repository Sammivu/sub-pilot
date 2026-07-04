package co.subpilot.auth.security;

import co.subpilot.internal.admin.security.InternalAdminAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthFilter authFilter;
    private final CsrfProtectionFilter csrfProtectionFilter;
    private final InternalAdminAuthFilter internalAdminAuthFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // Spring's built-in CSRF machinery is session-based
                // (CsrfTokenRepository backed by HttpSession), which doesn't
                // fit a stateless-JWT app. CsrfProtectionFilter below
                // implements the double-submit-cookie pattern instead,
                // which needs no server-side session. This disable() is not
                // "CSRF is off" — it's "we're not using Spring's session-based
                // implementation of it".
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public auth endpoints
                        .requestMatchers(HttpMethod.POST, "/v1/auth/signup", "/v1/auth/login",
                                "/v1/auth/refresh", "/v1/auth/logout").permitAll()
                        // Public plan pages (subscriber checkout)
                        .requestMatchers(HttpMethod.GET, "/v1/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/public/**").permitAll()
                        // Subscriber portal (token-based, no JWT)
                        .requestMatchers("/v1/portal/**").permitAll()
                        // Inbound Nomba webhooks
                        .requestMatchers(HttpMethod.POST, "/v1/webhooks/nomba").permitAll()
                        // Internal admin login is the one internal route
                        // reachable with no session yet — everything else
                        // under /v1/internal/** falls through to
                        // anyRequest().authenticated() below, which
                        // InternalAdminAuthFilter satisfies exactly the
                        // same way AuthFilter does for merchant routes.
                        .requestMatchers(HttpMethod.POST, "/v1/internal/auth/login").permitAll()
                        //Swagger
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // Actuator
                        .requestMatchers("/actuator/health").permitAll()
                        // Everything else requires auth
                        .anyRequest().authenticated()
                )
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                // Item 4 — runs immediately after AuthFilter so it can read
                // the COOKIE_AUTH_ATTRIBUTE AuthFilter just set.
                .addFilterAfter(csrfProtectionFilter, AuthFilter.class)
                // Independent of AuthFilter's position — see both filters'
                // mutual path-exclusion javadocs for why order between
                // these two specifically doesn't matter (they can never
                // both act on the same request).
                .addFilterBefore(internalAdminAuthFilter, AuthFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}