package io.github.changlu.openreach.security;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.observability.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Public attack-surface allowlist.
 *
 * <p>OpenReach intentionally exposes exactly four JSON POST APIs plus a small
 * classpath-only static website. Everything else is rejected before controller
 * dispatch. Multipart upload, dangerous methods and oversized/chunked bodies are
 * denied centrally.</p>
 */
@Component
public class AttackSurfaceFilter extends OncePerRequestFilter {
    private static final Logger apiLog = LoggerFactory.getLogger("OPENREACH.API");
    private static final Set<String> API_PATHS = Set.of(
            "/api/web/search",
            "/api/web/image-search",
            "/api/web/read",
            "/api/web/curl"
    );
    private static final Set<String> STATIC_EXACT_PATHS = Set.of(
            "/", "/index.html",
            "/docs", "/docs/", "/docs/index.html", "/docs/api.html",
            "/changelog", "/changelog/", "/changelog.html",
            "/community", "/community/",
            "/downloads/openreach-skill.zip"
    );

    private static final Set<String> MONITOR_PROTECTED_PATHS = Set.of(
            "/monitor", "/monitor/", "/monitor.html",
            "/assets/monitor.css", "/assets/monitor.js"
    );
    private static final Set<String> MONITOR_LOGIN_PATHS = Set.of(
            "/monitor/login", "/monitor/login/", "/monitor-login.html",
            "/assets/monitor-login.css", "/assets/monitor-login.js"
    );

    private final WebCapabilityProperties properties;
    private final MonitorAuthService monitorAuthService;

    public AttackSurfaceFilter(WebCapabilityProperties properties, MonitorAuthService monitorAuthService) {
        this.properties = properties;
        this.monitorAuthService = monitorAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        applySecurityHeaders(response);

        String path = request.getRequestURI();
        if (path == null || suspiciousPath(path)) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST", "Invalid request path");
            return;
        }

        if (API_PATHS.contains(path)) {
            handleApiRequest(request, response, filterChain);
            return;
        }

        if (isMonitorApiPath(path)) {
            handleMonitorApiRequest(request, response, filterChain);
            return;
        }

        if (MONITOR_PROTECTED_PATHS.contains(path)) {
            handleProtectedMonitorRequest(request, response, filterChain);
            return;
        }

        if (MONITOR_LOGIN_PATHS.contains(path) || "/monitor/logout".equals(path)) {
            handleMonitorAuthRequest(request, response, filterChain, path);
            return;
        }

        if (isStaticPath(path)) {
            if (!isMethod(request, "GET") && !isMethod(request, "HEAD")) {
                reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Only GET/HEAD are allowed for static resources");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // Deliberately hide all other framework/application endpoints, including
        // upload-like paths, actuator/debug/error handlers and accidental controllers.
        reject(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", "Resource not found");
    }


    private void handleMonitorApiRequest(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        applyMonitorBrowserPolicy(response);
        response.setHeader("Cache-Control", "no-store");
        if (!isMethod(request, "GET")) {
            reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "Monitor API only accepts GET requests");
            return;
        }
        if (!monitorAuthService.isAuthenticated(request)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "MONITOR_AUTH_REQUIRED",
                    "Monitor authentication required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isMonitorApiPath(String path) {
        return "/api/monitor/status".equals(path)
                || "/api/monitor/overview".equals(path)
                || "/api/monitor/trend".equals(path)
                || "/api/monitor/distribution".equals(path)
                || "/api/monitor/records".equals(path)
                || path.startsWith("/api/monitor/records/");
    }

    private void handleProtectedMonitorRequest(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        applyMonitorBrowserPolicy(response);
        if (!isMethod(request, "GET") && !isMethod(request, "HEAD")) {
            reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "Only GET/HEAD are allowed for the monitor console");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if (!monitorAuthService.isAuthenticated(request)) {
            response.sendRedirect("/monitor/login");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void handleMonitorAuthRequest(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain, String path)
            throws IOException, ServletException {
        applyMonitorBrowserPolicy(response);
        response.setHeader("Cache-Control", "no-store");

        if (path.startsWith("/assets/") || "/monitor-login.html".equals(path) || "/monitor/login".equals(path) || "/monitor/login/".equals(path)) {
            if (isMethod(request, "GET") || isMethod(request, "HEAD")) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        if (("/monitor/login".equals(path) || "/monitor/logout".equals(path)) && isMethod(request, "POST")) {
            String contentType = request.getContentType();
            if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
                reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                        "Monitor login only accepts form submissions");
                return;
            }
            long declared = request.getContentLengthLong();
            if (declared > 4096) {
                reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                        "Monitor form body exceeds configured limit");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Unsupported monitor authentication request");
    }

    private void handleApiRequest(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        if (!isMethod(request, "POST")) {
            reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Only POST is allowed");
            return;
        }

        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "UPLOAD_DISABLED", "File upload is disabled");
            return;
        }
        if (!isJsonContentType(contentType)) {
            reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Content-Type must be application/json");
            return;
        }

        int maxBodyBytes = Math.max(1024, properties.getSecurity().getMaxApiBodyBytes());
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Request body exceeds configured limit");
            return;
        }

        byte[] body;
        try {
            body = readLimited(request.getInputStream(), maxBodyBytes);
        } catch (PayloadTooLargeException ex) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Request body exceeds configured limit");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private byte[] readLimited(ServletInputStream input, int maxBytes) throws IOException, PayloadTooLargeException {
        try (input; ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new PayloadTooLargeException();
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private boolean isStaticPath(String path) {
        if (STATIC_EXACT_PATHS.contains(path)) return true;
        return path.startsWith("/assets/") && path.length() > "/assets/".length();
    }

    private boolean suspiciousPath(String raw) {
        if (raw.indexOf('\\') >= 0 || raw.indexOf(';') >= 0 || raw.contains("..") || raw.indexOf('%') >= 0) {
            return true;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= 0x1F || c == 0x7F) return true;
        }
        return false;
    }

    private boolean isMethod(HttpServletRequest request, String expected) {
        return expected.equalsIgnoreCase(request.getMethod());
    }

    private boolean isJsonContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return false;
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.includes(mediaType)
                    || ("application".equalsIgnoreCase(mediaType.getType())
                    && mediaType.getSubtype() != null && mediaType.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; font-src 'self'; connect-src 'none'; media-src 'none'; "
                        + "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
    }

    private void applyMonitorBrowserPolicy(HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; font-src 'self'; connect-src 'self'; media-src 'none'; "
                        + "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        apiLog.warn("[OPENREACH-API] request_rejected code={} status={} message={}", code, status, message);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        String traceId = TraceContext.traceId().replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"traceId\":\"" + traceId + "\",\"message\":\"" + escaped + "\"}");
    }

    private static final class PayloadTooLargeException extends Exception {}

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) {
                    if (readListener == null) return;
                    try {
                        if (isFinished()) readListener.onAllDataRead();
                        else readListener.onDataAvailable();
                    } catch (IOException ex) {
                        readListener.onError(ex);
                    }
                }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
