package io.github.changlu.openreach.security;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Minimal session authentication for the internal monitor console.
 *
 * <p>This deliberately avoids adding Spring Security just for the v0.1.3
 * monitor prototype, while still keeping the dashboard behind server-side
 * authentication rather than relying on hidden navigation or front-end checks.</p>
 */
@Component
public class MonitorAuthService {
    public static final String SESSION_KEY = "OPENREACH_MONITOR_AUTHENTICATED";

    private final WebCapabilityProperties properties;

    public MonitorAuthService(WebCapabilityProperties properties) {
        this.properties = properties;
    }

    public boolean isAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return false;
        try {
            return Boolean.TRUE.equals(session.getAttribute(SESSION_KEY));
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    public boolean authenticate(HttpServletRequest request, String username, String password) {
        WebCapabilityProperties.Monitor monitor = properties.getMonitor();
        if (!secureEquals(monitor.getUsername(), username) || !secureEquals(monitor.getPassword(), password)) {
            return false;
        }

        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_KEY, Boolean.TRUE);
        session.setMaxInactiveInterval(Math.max(300, monitor.getSessionTimeoutSeconds()));
        return true;
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private boolean secureEquals(String expected, String actual) {
        byte[] left = (expected == null ? "" : expected).getBytes(StandardCharsets.UTF_8);
        byte[] right = (actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
