package io.github.changlu.openreach.curl;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.security.UrlSafetyGuard;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Curl-specific SSRF guard.
 *
 * <p>In addition to the shared public-Web guard this class always locks Curl to
 * ports 80/443 and explicitly denies OpenReach itself. Self detection covers:</p>
 * <ul>
 *   <li>the inbound Host/serverName used to reach OpenReach;</li>
 *   <li>the current process/container network-interface addresses;</li>
 *   <li>the servlet local address/name;</li>
 *   <li>operator configured blocked/self host aliases.</li>
 * </ul>
 */
@Component
public class CurlTargetGuard {
    private final UrlSafetyGuard urlSafetyGuard;
    private final WebCapabilityProperties properties;

    public CurlTargetGuard(UrlSafetyGuard urlSafetyGuard, WebCapabilityProperties properties) {
        this.urlSafetyGuard = urlSafetyGuard;
        this.properties = properties;
    }

    public URI validate(String rawUrl, SelfTargetContext self) {
        URI uri = urlSafetyGuard.validate(rawUrl);
        int effectivePort = uri.getPort() == -1
                ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80)
                : uri.getPort();
        if (effectivePort != 80 && effectivePort != 443) {
            throw new BadRequestException("Curl only allows public HTTP/HTTPS ports 80/443");
        }

        String targetHost = normalizeHost(uri.getHost());
        if (targetHost == null) throw new BadRequestException("URL host is required");

        Set<String> selfHostNames = new HashSet<>();
        Set<String> selfAddresses = new HashSet<>();
        if (self != null) {
            addHost(selfHostNames, self.serverName());
            addHost(selfHostNames, self.localName());
            addHost(selfHostNames, hostFromHeader(self.hostHeader()));
            addAddress(selfAddresses, self.localAddr());
        }
        for (String configured : properties.getCurl().getBlockedHosts()) {
            addHost(selfHostNames, configured);
        }

        if (matchesBlockedHost(targetHost, selfHostNames)) {
            throw new BadRequestException("Curl target resolves to OpenReach itself; self requests are forbidden");
        }

        // Resolve the inbound/self host names as well. This blocks alternate public
        // aliases that terminate on the same address as the OpenReach endpoint, not
        // only the exact Host header used by the caller. This is intentionally
        // conservative because Curl must never become a self-bruteforce primitive.
        collectResolvedSelfHostAddresses(selfHostNames, selfAddresses);
        collectLocalInterfaceAddresses(selfAddresses);
        try {
            InetAddress[] targetAddresses = InetAddress.getAllByName(targetHost);
            for (InetAddress address : targetAddresses) {
                if (selfAddresses.contains(address.getHostAddress().toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException("Curl target resolves to OpenReach/local interface; self requests are forbidden");
                }
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            // UrlSafetyGuard already performs mandatory DNS resolution. If a second
            // self-check lookup races with DNS, fail closed rather than bypassing it.
            throw new BadRequestException("Curl target cannot be safely resolved");
        }
        return uri;
    }

    private boolean matchesBlockedHost(String targetHost, Set<String> blocked) {
        for (String value : blocked) {
            if (value == null || value.isBlank()) continue;
            String normalized = normalizeHost(value);
            if (normalized == null) continue;
            if (normalized.startsWith("*.")) {
                String suffix = normalized.substring(1); // includes leading dot
                if (targetHost.endsWith(suffix)) return true;
            } else if (targetHost.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private void collectResolvedSelfHostAddresses(Set<String> selfHostNames, Set<String> out) {
        for (String selfHost : selfHostNames) {
            if (selfHost == null || selfHost.isBlank() || selfHost.startsWith("*.")) continue;
            try {
                for (InetAddress address : InetAddress.getAllByName(selfHost)) {
                    out.add(address.getHostAddress().toLowerCase(Locale.ROOT));
                }
            } catch (Exception ignored) {
                // Exact hostname blocking remains active if a self alias is temporarily unresolvable.
            }
        }
    }

    private void collectLocalInterfaceAddresses(Set<String> out) {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    out.add(address.getHostAddress().toLowerCase(Locale.ROOT));
                }
            }
            InetAddress local = InetAddress.getLocalHost();
            if (local != null) out.add(local.getHostAddress().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            // The inbound host/local address checks remain active even in restricted containers.
        }
    }

    private void addAddress(Set<String> out, String value) {
        if (value == null || value.isBlank()) return;
        try {
            out.add(InetAddress.getByName(value.trim()).getHostAddress().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            out.add(value.trim().toLowerCase(Locale.ROOT));
        }
    }

    private void addHost(Set<String> out, String value) {
        String normalized = normalizeHost(value);
        if (normalized != null) out.add(normalized);
    }

    private String hostFromHeader(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) return null;
        String value = hostHeader.trim();
        try {
            URI parsed = URI.create("http://" + value);
            return parsed.getHost();
        } catch (Exception ignored) {
            int colon = value.indexOf(':');
            return colon > 0 ? value.substring(0, colon) : value;
        }
    }

    private String normalizeHost(String value) {
        if (value == null) return null;
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return null;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }
}
