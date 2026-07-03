package co.subpilot.auth.security;

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
    private final co.subpilot.common.admin.AdminApiKeyFilter adminApiKeyFilter;
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
                        // Admin endpoints — auth handled entirely by AdminApiKeyFilter
                        // (static shared secret, not a merchant JWT), so this must be
                        // permitAll here or AuthFilter's "no valid session" 401 would
                        // fire first and AdminApiKeyFilter would never even run.
                        .requestMatchers("/v1/admin/**").permitAll()
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
                .addFilterBefore(adminApiKeyFilter, AuthFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}