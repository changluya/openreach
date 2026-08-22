package io.github.changlu.openreach.curl;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.security.UrlSafetyGuard;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurlTargetGuardTest {

    @Test
    void rejectsTheSamePublicHostUsedToReachOpenReach() {
        CurlTargetGuard guard = guard(new WebCapabilityProperties());
        SelfTargetContext self = new SelfTargetContext(
                "openreach.example.com", "openreach", "172.18.0.2", "openreach.example.com:443");

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> guard.validate("https://openreach.example.com/api/web/search", self));

        assertTrue(ex.getMessage().contains("itself"));
    }

    @Test
    void rejectsConfiguredAlternativeSelfAliasBeforeDnsLookup() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getCurl().setBlockedHosts(List.of("openreach-alt.example.com", "*.self.example"));
        CurlTargetGuard guard = guard(props);

        assertThrows(BadRequestException.class,
                () -> guard.validate("https://openreach-alt.example.com/", null));
        assertThrows(BadRequestException.class,
                () -> guard.validate("https://api.self.example/", null));
    }

    @Test
    void rejectsServletLocalAddressAsSelfTarget() {
        CurlTargetGuard guard = guard(new WebCapabilityProperties());
        SelfTargetContext self = new SelfTargetContext("public.example", "openreach", "127.0.0.1", "public.example");

        assertThrows(BadRequestException.class,
                () -> guard.validate("http://127.0.0.1/", self));
    }

    @Test
    void preservesPublicTargetWhenItIsNotSelf() {
        CurlTargetGuard guard = guard(new WebCapabilityProperties());
        URI uri = guard.validate("https://93.184.216.34/source.txt",
                new SelfTargetContext("openreach.example.com", "openreach", "172.18.0.2", "openreach.example.com"));
        assertEquals("93.184.216.34", uri.getHost());
    }

    private CurlTargetGuard guard(WebCapabilityProperties props) {
        UrlSafetyGuard passThrough = new UrlSafetyGuard() {
            @Override
            public URI validate(String rawUrl) {
                return URI.create(rawUrl);
            }
        };
        return new CurlTargetGuard(passThrough, props);
    }
}
