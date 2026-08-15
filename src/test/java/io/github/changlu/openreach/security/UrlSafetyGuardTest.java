package io.github.changlu.openreach.security;

import io.github.changlu.openreach.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlSafetyGuardTest {
    private final UrlSafetyGuard guard = new UrlSafetyGuard();

    @Test void rejectsLocalhost() {
        assertThrows(BadRequestException.class, () -> guard.validate("http://localhost:8080/test"));
    }

    @Test void rejectsLoopbackIp() {
        assertThrows(BadRequestException.class, () -> guard.validate("http://127.0.0.1/test"));
    }

    @Test void rejectsPrivateIpv4() {
        assertThrows(BadRequestException.class, () -> guard.validate("http://10.0.0.1/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://192.168.1.10/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://172.16.1.10/test"));
    }

    @Test void rejectsCloudMetadataLinkLocal() {
        assertThrows(BadRequestException.class, () -> guard.validate("http://169.254.169.254/latest/meta-data"));
    }

    @Test void rejectsUnsupportedScheme() {
        assertThrows(BadRequestException.class, () -> guard.validate("file:///etc/passwd"));
    }

    @Test void rejectsUserInfoInUrl() {
        assertThrows(BadRequestException.class, () -> guard.validate("https://user:pass@example.com/"));
    }
}
