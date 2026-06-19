package dev.ohs.player.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

  @Mock private JwtTokenValidator jwtTokenValidator;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private FilterChain chain;

  private JwtAuthFilter filter;

  @BeforeEach
  void setUp() throws Exception {
    filter = new JwtAuthFilter(jwtTokenValidator);
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
  }

  @Test
  void doFilter_MissingAuthHeader_Returns401() throws Exception {
    when(request.getHeader("Authorization")).thenReturn(null);

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_NonBearerScheme_Returns401() throws Exception {
    when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_InvalidToken_Returns401() throws Exception {
    when(request.getHeader("Authorization")).thenReturn("Bearer bad.token.here");
    when(jwtTokenValidator.validate("bad.token.here")).thenThrow(new Exception("invalid"));

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_ValidToken_SetsAttributeAndContinues() throws Exception {
    AuthenticatedUser user = new AuthenticatedUser("user-id", "alice", Set.of("users.manage"));
    when(request.getHeader("Authorization")).thenReturn("Bearer valid.jwt.token");
    when(jwtTokenValidator.validate("valid.jwt.token")).thenReturn(user);

    filter.doFilter(request, response, chain);

    verify(request).setAttribute(eq(AuthorizationHandler.AUTH_USER_ATTRIBUTE), eq(user));
    verify(chain).doFilter(request, response);
    verify(response, never()).setStatus(anyInt());
  }

  @Test
  void doFilter_BearerTokenWithExtraWhitespace_StripsBeforeValidating() throws Exception {
    AuthenticatedUser user = new AuthenticatedUser("user-id", "alice", Set.of("users.view"));
    when(request.getHeader("Authorization")).thenReturn("Bearer   padded.token  ");
    when(jwtTokenValidator.validate("padded.token")).thenReturn(user);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
  }
}
