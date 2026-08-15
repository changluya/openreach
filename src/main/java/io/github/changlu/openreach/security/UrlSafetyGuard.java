package io.github.changlu.openreach.security;

import io.github.changlu.openreach.common.BadRequestException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

@Component
public class UrlSafetyGuard {

    public URI validate(String rawUrl) {
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

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw new BadRequestException("Local/internal hosts are not allowed");
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new BadRequestException("URL host cannot be resolved");
            for (InetAddress address : addresses) {
                if (isForbidden(address)) {
                    throw new BadRequestException("Private/local/link-local target is not allowed: " + address.getHostAddress());
                }
            }
        } catch (UnknownHostException ex) {
            throw new BadRequestException("URL host cannot be resolved");
        }

        return uri;
    }

    private boolean isForbidden(InetAddress address) {
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
            return a == 0
                    || a == 10
                    || a == 127
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || a >= 224;
        }

        // IPv6 unique local fc00::/7 and unspecified ::
        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            boolean uniqueLocal = (first & 0xFE) == 0xFC;
            boolean unspecified = address.isAnyLocalAddress();
            return uniqueLocal || unspecified;
        }
        return false;
    }
}
