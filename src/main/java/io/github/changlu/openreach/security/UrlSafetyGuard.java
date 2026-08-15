package io.github.changlu.openreach.security;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * SSRF guard for all user-derived remote fetches.
 *
 * <p>Only public HTTP(S) endpoints on explicitly allowed ports are accepted.
 * Every redirect must be validated again by the caller.</p>
 */
@Component
public class UrlSafetyGuard {
    private final List<Integer> allowedPorts;

    /** Test-friendly constructor with secure public-Web defaults. */
    public UrlSafetyGuard() {
        this.allowedPorts = List.of(80, 443);
    }

    @Autowired
    public UrlSafetyGuard(WebCapabilityProperties properties) {
        List<Integer> configured = properties.getRead().getAllowedPorts();
        this.allowedPorts = configured == null || configured.isEmpty() ? List.of(80, 443) : List.copyOf(configured);
    }

    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new BadRequestException("URL is required");
        if (containsControl(rawUrl)) throw new BadRequestException("URL contains control characters");

        final URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception ex) {
            throw new BadRequestException("Invalid URL");
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BadRequestException("Only http/https URLs are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new BadRequestException("URLs containing user-info are not allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BadRequestException("URL host is required");
        }
        if (uri.getRawAuthority() != null && uri.getRawAuthority().contains("\\")) {
            throw new BadRequestException("Backslashes are not allowed in URL authority");
        }

        int effectivePort = uri.getPort() == -1
                ? ("https".equalsIgnoreCase(scheme) ? 443 : 80)
                : uri.getPort();
        if (!allowedPorts.contains(effectivePort)) {
            throw new BadRequestException("URL port is not allowed; allowed ports: " + allowedPorts);
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.equals("metadata.google.internal")) {
            throw new BadRequestException("Local/internal hosts are not allowed");
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new BadRequestException("URL host cannot be resolved");
            for (InetAddress address : addresses) {
                if (isForbidden(address)) {
                    throw new BadRequestException("Private/local/reserved target is not allowed");
                }
            }
        } catch (UnknownHostException ex) {
            throw new BadRequestException("URL host cannot be resolved");
        }

        return uri;
    }

    private boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || c == 0x7F) return true;
        }
        return false;
    }

    boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);
            return a == 0
                    || a == 10
                    || a == 127
                    || (a == 100 && b >= 64 && b <= 127)       // CGNAT 100.64/10
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 0 && c == 0)          // IETF protocol assignments
                    || (a == 192 && b == 0 && c == 2)          // TEST-NET-1
                    || (a == 192 && b == 168)
                    || (a == 198 && (b == 18 || b == 19))      // benchmark 198.18/15
                    || (a == 198 && b == 51 && c == 100)       // TEST-NET-2
                    || (a == 203 && b == 0 && c == 113)        // TEST-NET-3
                    || a >= 224;
        }

        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean uniqueLocal = (first & 0xFE) == 0xFC;       // fc00::/7
            boolean documentation = first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0D && Byte.toUnsignedInt(bytes[3]) == 0xB8; // 2001:db8::/32
            return uniqueLocal || documentation;
        }
        return true;
    }
}
