package dev.ohs.player.auth;

import dev.ohs.player.endpoints.ServletResponseUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class JwtAuthFilter implements Filter {

  private static final Logger logger = LoggerFactory.getLogger(JwtAuthFilter.class);
  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenValidator jwtTokenValidator;

  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    HttpServletResponse response = (HttpServletResponse) servletResponse;

    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || authHeader.isBlank()) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_UNAUTHORIZED, "Authorization header is required");
      return;
    }
    if (!authHeader.startsWith(BEARER_PREFIX)) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_UNAUTHORIZED,
          "Authorization header must use Bearer scheme");
      return;
    }

    String token = authHeader.substring(BEARER_PREFIX.length()).strip();
    try {
      AuthenticatedUser user = jwtTokenValidator.validate(token);
      request.setAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE, user);
      chain.doFilter(request, response);
    } catch (Exception e) {
      logger.debug("JWT validation failed: {}", e.getMessage());
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
    }
  }
}
