package io.github.changlu.openreach.observability;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the real client IP behind a trusted reverse proxy without blindly trusting
 * spoofable forwarding headers from direct clients.
 */
final class ClientIpResolver {
    private static final int MAX_IP_LENGTH = 64;

    private ClientIpResolver() {
    }

    static String resolve(String remoteAddr,
                          String xForwardedFor,
                          String xRealIp,
                          boolean trustProxyHeaders,
                          List<String> trustedProxyCidrs) {
        String remote = normalizeIp(remoteAddr);
        if (!trustProxyHeaders || remote == null || !isTrusted(remote, trustedProxyCidrs)) {
            return display(remote);
        }

        List<String> forwarded = parseForwardedFor(xForwardedFor);
        for (int i = forwarded.size() - 1; i >= 0; i--) {
            String candidate = forwarded.get(i);
            if (!isTrusted(candidate, trustedProxyCidrs)) {
                return display(candidate);
            }
        }

        String realIp = normalizeIp(xRealIp);
        if (realIp != null) {
            return display(realIp);
        }

        // If every forwarding hop is trusted, retain the left-most forwarded hop as the
        // best available origin instead of collapsing back to the Docker bridge address.
        if (!forwarded.isEmpty()) {
            return display(forwarded.get(0));
        }
        return display(remote);
    }

    static boolean isTrusted(String ip, List<String> trustedProxyCidrs) {
        byte[] address = parseLiteralAddress(ip);
        if (address == null || trustedProxyCidrs == null || trustedProxyCidrs.isEmpty()) {
            return false;
        }
        for (String cidrText : trustedProxyCidrs) {
            Cidr cidr = Cidr.parse(cidrText);
            if (cidr != null && cidr.matches(address)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> parseForwardedFor(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : value.split(",")) {
            String ip = normalizeIp(token);
            if (ip != null) result.add(ip);
        }
        return result;
    }

    private static String normalizeIp(String value) {
        if (value == null) return null;
        String cleaned = value.replace("\r", "").replace("\n", "").replace("\t", "").trim();
        if (cleaned.isEmpty() || "unknown".equalsIgnoreCase(cleaned)) return null;
        if (cleaned.length() > 128) return null;

        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.startsWith("[") && cleaned.contains("]")) {
            int end = cleaned.indexOf(']');
            cleaned = cleaned.substring(1, end);
        } else if (looksLikeIpv4WithPort(cleaned)) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf(':'));
        }

        return parseLiteralAddress(cleaned) == null ? null : cleaned;
    }

    private static boolean looksLikeIpv4WithPort(String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || value.indexOf(':') != colon || value.indexOf('.') < 0) return false;
        String port = value.substring(colon + 1);
        if (port.isEmpty()) return false;
        for (int i = 0; i < port.length(); i++) {
            if (!Character.isDigit(port.charAt(i))) return false;
        }
        return true;
    }

    private static byte[] parseLiteralAddress(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9A-Fa-f:.]+")) return null;
            try {
                return InetAddress.getByName(value).getAddress();
            } catch (Exception ignored) {
                return null;
            }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return null;
            int n;
            try {
                n = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return null;
            }
            if (n < 0 || n > 255) return null;
            bytes[i] = (byte) n;
        }
        return bytes;
    }

    private static String display(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.length() <= MAX_IP_LENGTH ? value : value.substring(0, MAX_IP_LENGTH);
    }

    private record Cidr(byte[] network, int prefixBits) {
        static Cidr parse(String text) {
            if (text == null || text.isBlank()) return null;
            String value = text.trim().toLowerCase(Locale.ROOT);
            int slash = value.indexOf('/');
            String ip = slash >= 0 ? value.substring(0, slash) : value;
            byte[] address = parseLiteralAddress(ip);
            if (address == null) return null;
            int maxBits = address.length * 8;
            int prefix = maxBits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(value.substring(slash + 1));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            if (prefix < 0 || prefix > maxBits) return null;
            return new Cidr(address, prefix);
        }

        boolean matches(byte[] address) {
            if (address == null || address.length != network.length) return false;
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = 0xFF << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
