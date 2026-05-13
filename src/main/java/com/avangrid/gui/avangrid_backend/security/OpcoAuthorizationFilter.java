package com.avangrid.gui.avangrid_backend.security;

import com.avangrid.gui.avangrid_backend.service.OpcoService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Spring Security filter that enforces OPCO-level access control on all protected
 * VRS (VPI Recording Service) API endpoints.
 *
 * <p>The filter runs once per request (extends {@link OncePerRequestFilter}) and is
 * inserted into the Spring Security filter chain. It intercepts requests to the
 * protected paths listed in {@link #PROTECTED_PATHS} and verifies that the
 * authenticated user's JWT roles include access to the OPCO referenced in the request.
 *
 * <p>OPCO extraction strategy:
 * <ul>
 *   <li><strong>GET requests</strong> — the OPCO code is read from the {@code opco}
 *       query parameter (e.g., {@code /api/v1/metadata?opco=CMP})</li>
 *   <li><strong>POST requests</strong> — the request body is read once in its entirety,
 *       the {@code "opco"} field is extracted from the raw JSON string, and the body bytes
 *       are cached in a {@link CachedBodyRequestWrapper} so the downstream controller
 *       can still read the body as normal via {@code getInputStream()} or {@code getReader()}</li>
 * </ul>
 *
 * <p>Access denial outcomes:
 * <ul>
 *   <li>Missing OPCO → HTTP 400 Bad Request</li>
 *   <li>OPCO present but user not authorised → HTTP 403 Forbidden</li>
 * </ul>
 *
 * <p>Requests to non-protected paths and requests without a valid JWT pass through
 * the filter unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpcoAuthorizationFilter extends OncePerRequestFilter {

    /** Service used to evaluate whether the authenticated user has access to a given OPCO. */
    private final OpcoService opcoService;

    /**
     * Set of URI paths that require OPCO-level access verification.
     * Any request whose URI is not in this set passes through the filter without inspection.
     */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/search",
            "/api/v1/recording",
            "/api/v1/download",
            "/api/v1/metadata"
    );

    /**
     * Core filter method executed once per HTTP request.
     *
     * <p>Processing flow:
     * <ol>
     *   <li>If the request path is not in {@link #PROTECTED_PATHS}, pass through immediately</li>
     *   <li>Extract the {@link Jwt} from the Spring Security context; pass through if absent
     *       (unauthenticated requests are handled by the upstream authentication filter)</li>
     *   <li>For GET requests — read the OPCO from the {@code opco} query parameter and validate</li>
     *   <li>For POST requests — consume the body once, extract the OPCO from the raw JSON,
     *       validate access, and wrap the request with {@link CachedBodyRequestWrapper}
     *       so the controller can still read the full body downstream</li>
     * </ol>
     *
     * @param request     The incoming HTTP request
     * @param response    The HTTP response (used to write 400/403 error responses on denial)
     * @param filterChain The remaining filter chain to continue processing when access is granted
     * @throws ServletException if a servlet-level error occurs
     * @throws IOException      if reading the request body or writing the error response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (!PROTECTED_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        Jwt jwt = extractJwt();
        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }


        if ("GET".equalsIgnoreCase(request.getMethod())) {
            String opco = request.getParameter("opco");
            if (!checkOpco(jwt, opco, request.getRequestURI(), response)) return;
            filterChain.doFilter(request, response);
            return;
        }

        // POST — read body once, cache it, wrap request so controller can re-read
        byte[] bodyBytes = request.getInputStream().readAllBytes();
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
        String opco = extractOpcoFromJson(bodyStr);

        if (!checkOpco(jwt, opco, request.getRequestURI(), response)) return;

        // Wrap with cached body so controller gets the full body again
        CachedBodyRequestWrapper wrappedRequest =
                new CachedBodyRequestWrapper(request, bodyBytes);

        filterChain.doFilter(wrappedRequest, response);
    }

    /**
     * Validates the extracted OPCO value against the authenticated user's access rights.
     *
     * <p>Writes an HTTP error response directly when validation fails, preventing the
     * request from reaching the controller:
     * <ul>
     *   <li>Null or blank OPCO → HTTP 400 Bad Request with message {@code "Missing opco"}</li>
     *   <li>OPCO present but not in the user's allowed set → HTTP 403 Forbidden</li>
     * </ul>
     *
     * @param jwt      The authenticated user's JWT token
     * @param opco     The OPCO value extracted from the request (query param or request body)
     * @param path     The request URI, used in warning log messages
     * @param response The HTTP response used to write denial errors
     * @return {@code true} if the OPCO is valid and the user has access; {@code false} otherwise
     * @throws IOException if writing the error response fails
     */
    private boolean checkOpco(Jwt jwt, String opco,
                              String path, HttpServletResponse response)
            throws IOException {
        if (opco == null || opco.isBlank()) {
            log.warn("Request to {} missing opco", path);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing opco");
            return false;
        }
        if (!opcoService.hasOpcoAccess(jwt, opco)) {
            log.warn("Subject '{}' denied access to opco '{}'", jwt.getSubject(), opco);
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Access denied for opco: " + opco);
            return false;
        }
        return true;
    }

    /**
     * Extracts the {@link Jwt} token from the Spring Security context.
     * Returns {@code null} if the current authentication is absent or is not a
     * {@link JwtAuthenticationToken} (e.g., anonymous or non-JWT authentication).
     * In that case the filter passes the request through and defers to the upstream
     * authentication filter to handle the unauthenticated state.
     *
     * @return The {@link Jwt} from the security context, or {@code null} if unavailable
     */
    private Jwt extractJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        return null;
    }

    /**
     * Extracts the value of the {@code "opco"} field from a raw JSON request body string
     * using lightweight string scanning — no JSON library dependency.
     *
     * <p>The parser locates the {@code "opco"} key, advances past the colon separator,
     * and extracts the content between the next pair of double-quote characters.
     * This is intentionally simple and expects the OPCO value to be a plain JSON string.
     *
     * <p>Returns {@code null} when:
     * <ul>
     *   <li>The JSON string is null or blank</li>
     *   <li>The {@code "opco"} key is not present</li>
     *   <li>The expected colon or quote delimiters are not found after the key</li>
     * </ul>
     *
     * @param json Raw JSON request body as a string
     * @return The extracted OPCO string value, or {@code null} if not found
     */
    private String extractOpcoFromJson(String json) {
        if (json == null || json.isBlank()) return null;
        int idx = json.indexOf("\"opco\"");
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        int quoteStart = json.indexOf('"', colon + 1);
        if (quoteStart == -1) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd == -1) return null;
        return json.substring(quoteStart + 1, quoteEnd).trim();
    }

    // ---------------------------------------------------------------
    // Inner class — stores body bytes and replays them on every read
    // ---------------------------------------------------------------

    /**
     * An {@link HttpServletRequestWrapper} that caches the request body bytes in memory
     * and replays them on every call to {@link #getInputStream()} or {@link #getReader()}.
     *
     * <p>This wrapper is necessary because the Servlet API only allows the request
     * {@link ServletInputStream} to be consumed once. After the filter reads the POST body
     * to extract the OPCO field, the original stream is exhausted. Wrapping the request
     * with this class ensures the downstream Spring MVC controller can still deserialize
     * the full request body via {@code @RequestBody} as if it were reading for the first time.
     */
    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        /** Cached copy of the original request body bytes. */
        private final byte[] cachedBody;

        /**
         * Constructs a new {@code CachedBodyRequestWrapper} with the provided body bytes.
         *
         * @param request    The original {@link HttpServletRequest} to wrap
         * @param cachedBody The already-consumed request body bytes to replay on subsequent reads
         */
        public CachedBodyRequestWrapper(HttpServletRequest request,
                                        byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

        /**
         * Returns a fresh {@link ServletInputStream} backed by the cached body bytes.
         * Each call creates a new {@link ByteArrayInputStream} from the same byte array,
         * so the body can be read multiple times without exhaustion.
         *
         * @return A new {@link ServletInputStream} that reads from the cached body bytes
         */
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream =
                    new ByteArrayInputStream(cachedBody);

            return new ServletInputStream() {
                @Override
                public int read() {
                    return byteArrayInputStream.read();
                }

                @Override
                public boolean isFinished() {
                    return byteArrayInputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // not needed for synchronous servlet
                }
            };
        }

        /**
         * Returns a {@link BufferedReader} wrapping the cached body input stream,
         * decoded as UTF-8. Provided for compatibility with code that accesses the
         * request body via {@link HttpServletRequest#getReader()} rather than
         * {@link HttpServletRequest#getInputStream()}.
         *
         * @return A UTF-8 {@link BufferedReader} over the cached request body
         */
        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
