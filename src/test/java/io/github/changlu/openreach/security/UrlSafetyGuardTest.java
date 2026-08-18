package io.github.changlu.openreach.security;

import io.github.changlu.openreach.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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


    @Test void privateAttachmentUrlReportsPrivateTargetInsteadOfOnlyPortError() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> guard.validate("http://172.16.114.23:8999/images/chat/example.png"));
        assertTrue(ex.getMessage().contains("Private/local/reserved"));
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

    @Test void rejectsUnexpectedPortsToReduceSsrfSurface() {
        assertThrows(BadRequestException.class, () -> guard.validate("https://example.com:8443/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://example.com:8080/test"));
    }

    @Test void rejectsCarrierGradeNatAndDocumentationRanges() {
        assertThrows(BadRequestException.class, () -> guard.validate("http://100.64.0.1/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://192.0.2.1/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://198.51.100.1/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://203.0.113.1/test"));
    }

    @Test void rejectsIpv6AndAlternativeLoopbackForms() {
        assertThrows(BadRequestException.class, () -> guard.validate("http://[::1]/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://[fc00::1]/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://[fe80::1]/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://[2001:db8::1]/test"));
        assertThrows(BadRequestException.class, () -> guard.validate("http://2130706433/test"));
    }

    @Test void rejectsControlCharacters() {
        assertThrows(BadRequestException.class, () -> guard.validate("https://example.com/a\nb"));
    }
}
