package co.hila.api.identity;

import co.hila.api.shared.Database;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.stereotype.Service;

@Service
public class AccountSessions {
  private static final String VERSION = AccountSessions.class.getName() + ".version";
  private final IdentityService identity;
  private final Database db;
  private final HttpSessionSecurityContextRepository contextRepository =
      new HttpSessionSecurityContextRepository();
  private final HttpSessionCsrfTokenRepository csrfRepository =
      new HttpSessionCsrfTokenRepository();
  private final AuthenticationManager authenticationManager;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final LogoutHandler logoutHandler;

  public AccountSessions(IdentityService identity, Database db) {
    this.identity = identity;
    this.db = db;
    authenticationManager =
        request -> {
          var login =
              identity.authenticateAccount(request.getName(), (String) request.getCredentials());
          var authentication = principal(login.id());
          authentication.setDetails(login.sessionVersion());
          return authentication;
        };
    sessionStrategy =
        new CompositeSessionAuthenticationStrategy(
            java.util.List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfRepository)));
    logoutHandler =
        new CompositeLogoutHandler(
            new CsrfLogoutHandler(csrfRepository), new SecurityContextLogoutHandler());
  }

  public HttpSessionSecurityContextRepository contextRepository() {
    return contextRepository;
  }

  public HttpSessionCsrfTokenRepository csrfRepository() {
    return csrfRepository;
  }

  private UsernamePasswordAuthenticationToken principal(UUID id) {
    return UsernamePasswordAuthenticationToken.authenticated(
        id.toString(),
        null,
        identity.roles(id).stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList());
  }

  public UUID login(
      String email, String password, HttpServletRequest request, HttpServletResponse response) {
    var authentication =
        authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(email, password));
    request.getSession();
    sessionStrategy.onAuthentication(authentication, request, response);
    request.getSession().setAttribute(VERSION, authentication.getDetails());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contextRepository.saveContext(context, request, response);
    return UUID.fromString(authentication.getName());
  }

  public void logout(HttpServletRequest request, HttpServletResponse response) {
    logoutHandler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
  }

  public void check(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null) {
      var session = request.getSession(false);
      var storedVersion = session == null ? null : (Long) session.getAttribute(VERSION);
      UUID id = UUID.fromString(authentication.getName());
      var versions =
          db.jdbc.queryForList(
              "SELECT session_version FROM users WHERE id=? AND status='ACTIVE' AND"
                  + " email_verified_at IS NOT NULL",
              Long.class,
              id);
      if (versions.isEmpty()
          || versions.get(0) != (storedVersion == null ? 0L : storedVersion.longValue())) {
        logout(request, response);
      } else {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(principal(id));
        SecurityContextHolder.setContext(context);
      }
    }
    chain.doFilter(request, response);
  }

  public static void revoke(Database db, UUID id) {
    db.jdbc.update("UPDATE users SET session_version=session_version+1 WHERE id=?", id);
    db.jdbc.update("DELETE FROM spring_session WHERE principal_name=?", id.toString());
  }
}
