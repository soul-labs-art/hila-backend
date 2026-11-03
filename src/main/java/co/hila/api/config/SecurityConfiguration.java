package co.hila.api.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SecurityConfiguration {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService accountsOnly() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Use the account session endpoint.");
    };
  }

  @Bean
  DefaultCookieSerializer cookie(org.springframework.core.env.Environment env) {
    var c = new DefaultCookieSerializer();
    c.setCookieName("JSESSIONID");
    c.setCookiePath("/");
    c.setSameSite("Lax");
    c.setUseHttpOnlyCookie(true);
    if (java.util.Arrays.asList(env.getActiveProfiles()).contains("prod"))
      c.setUseSecureCookie(true);
    return c;
  }

  @Bean
  SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        org.springframework.http.HttpMethod.DELETE, "/api/v1/auth/sessions/current")
                    .authenticated()
                    .requestMatchers(
                        "/api/v1/health",
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/api/v1/auth/**",
                        "/api/v1/policies/current",
                        "/api/v1/catalogs/territories",
                        "/api/v1/volunteers/*/public-profile",
                        "/api/v1/organizations/*/public-profile",
                        "/api/v1/profile-images/*")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(
            c ->
                c.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, error) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Inicia"
                                      + " sesión para"
                                      + " continuar.\",\"fieldErrors\":[],\"traceId\":\""
                                      + java.util.UUID.randomUUID()
                                      + "\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, error) -> {
                          res.setStatus(403);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\""
                                      + (error instanceof CsrfException
                                          ? "CSRF_INVALID"
                                          : "FORBIDDEN")
                                      + "\",\"message\":\"No fue posible autorizar esta"
                                      + " solicitud.\",\"fieldErrors\":[],\"traceId\":\""
                                      + java.util.UUID.randomUUID()
                                      + "\"}");
                        }))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .build();
  }
}
