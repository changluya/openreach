package io.github.changlu.openreach.observability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpResolverTest {
    private static final List<String> TRUSTED = List.of("127.0.0.1/32", "::1/128", "172.16.0.0/12");

    @Test
    void usesRemoteAddressWhenProxyHeadersAreDisabled() {
        assertEquals("172.17.0.1", ClientIpResolver.resolve(
                "172.17.0.1", "203.0.113.10", "203.0.113.10", false, TRUSTED));
    }

    @Test
    void ignoresSpoofedForwardingHeadersFromUntrustedDirectClient() {
        assertEquals("198.51.100.20", ClientIpResolver.resolve(
                "198.51.100.20", "1.2.3.4", "1.2.3.4", true, TRUSTED));
    }

    @Test
    void resolvesRealClientBehindDockerNginx() {
        assertEquals("203.0.113.10", ClientIpResolver.resolve(
                "172.17.0.1", "203.0.113.10", "203.0.113.10", true, TRUSTED));
    }

    @Test
    void walksForwardedForFromRightToLeftToDefeatSpoofedLeftMostValue() {
        assertEquals("203.0.113.10", ClientIpResolver.resolve(
                "172.17.0.1", "1.2.3.4, 203.0.113.10", "203.0.113.10", true, TRUSTED));
    }

    @Test
    void skipsTrustedProxyHopsAndReturnsFirstUntrustedHop() {
        assertEquals("198.51.100.33", ClientIpResolver.resolve(
                "172.17.0.1", "198.51.100.33, 172.18.0.2", "198.51.100.33", true, TRUSTED));
    }

    @Test
    void fallsBackToXRealIpWhenForwardedForIsMissing() {
        assertEquals("198.51.100.44", ClientIpResolver.resolve(
                "172.17.0.1", null, "198.51.100.44", true, TRUSTED));
    }

    @Test
    void acceptsIpv4PortAndBracketedIpv6ForwardingValues() {
        assertEquals("203.0.113.55", ClientIpResolver.resolve(
                "172.17.0.1", "203.0.113.55:443", null, true, TRUSTED));
        assertEquals("2001:db8::8", ClientIpResolver.resolve(
                "172.17.0.1", "[2001:db8::8]:443", null, true, TRUSTED));
    }

    @Test
    void rejectsMalformedForwardedValuesAndFallsBackSafely() {
        assertEquals("172.17.0.1", ClientIpResolver.resolve(
                "172.17.0.1", "evil.example.com", "unknown", true, TRUSTED));
    }

    @Test
    void matchesTrustedProxyCidrs() {
        assertTrue(ClientIpResolver.isTrusted("172.17.0.1", TRUSTED));
        assertTrue(ClientIpResolver.isTrusted("172.31.255.254", TRUSTED));
        assertTrue(ClientIpResolver.isTrusted("127.0.0.1", TRUSTED));
        assertTrue(ClientIpResolver.isTrusted("::1", TRUSTED));
        assertFalse(ClientIpResolver.isTrusted("172.32.0.1", TRUSTED));
        assertFalse(ClientIpResolver.isTrusted("203.0.113.10", TRUSTED));
    }
}
