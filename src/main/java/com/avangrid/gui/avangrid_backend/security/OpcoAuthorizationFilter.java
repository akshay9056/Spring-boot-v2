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

@Slf4j
@Component
@RequiredArgsConstructor
public class OpcoAuthorizationFilter extends OncePerRequestFilter {

    private final OpcoService opcoService;

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/search",
            "/api/v1/recording",
            "/api/v1/download",
            "/api/v1/metadata"
    );

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

        // GET — no body involved
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

    private Jwt extractJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        return null;
    }

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
    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        public CachedBodyRequestWrapper(HttpServletRequest request,
                                        byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

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

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}