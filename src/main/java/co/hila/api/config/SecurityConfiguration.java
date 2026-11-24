package co.hila.api.config;

import co.hila.api.identity.AccountSessions;
import co.hila.api.shared.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
    var cookie = new DefaultCookieSerializer();
    cookie.setCookieName("JSESSIONID");
    cookie.setCookiePath("/");
    cookie.setSameSite("Lax");
    cookie.setUseHttpOnlyCookie(true);
    if (env.matchesProfiles("prod")) cookie.setUseSecureCookie(true);
    return cookie;
  }

  @Bean
  SecurityFilterChain applicationSecurity(
      HttpSecurity http, AccountSessions sessions, ObjectMapper json) throws Exception {
    var activeSession =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws IOException, ServletException {
            sessions.check(request, response, chain);
          }
        };
    return http.securityContext(c -> c.securityContextRepository(sessions.contextRepository()))
        .addFilterAfter(activeSession, SecurityContextHolderFilter.class)
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/health",
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/api/v1/auth/csrf",
                        "/api/v1/policies/current",
                        "/api/v1/catalogs/territories",
                        "/api/v1/catalogs/skills",
                        "/api/v1/catalogs/resources",
                        "/api/v1/needs",
                        "/api/v1/needs/*",
                        "/api/v1/volunteers/*/public-profile",
                        "/api/v1/organizations/*/public-profile",
                        "/api/v1/profile-images/*")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/registrations",
                        "/api/v1/auth/sessions",
                        "/api/v1/auth/email-verifications",
                        "/api/v1/auth/email-verification-requests",
                        "/api/v1/auth/password-reset-requests",
                        "/api/v1/auth/password-resets")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .csrf(
            c ->
                c.csrfTokenRepository(sessions.csrfRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, error) ->
                            write(
                                json,
                                res,
                                401,
                                "AUTHENTICATION_REQUIRED",
                                "Inicia sesión para continuar."))
                    .accessDeniedHandler(
                        (req, res, error) ->
                            write(
                                json,
                                res,
                                403,
                                error instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                "No fue posible autorizar esta solicitud.")))
        .requestCache(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .build();
  }

  private void write(
      ObjectMapper json, HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.getWriter().write(json.writeValueAsString(ApiErrors.body(code, message)));
  }
}
