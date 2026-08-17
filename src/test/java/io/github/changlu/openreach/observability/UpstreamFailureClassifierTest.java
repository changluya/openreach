package io.github.changlu.openreach.observability;

import io.github.changlu.openreach.common.UpstreamException;
import org.junit.jupiter.api.Test;
import java.net.http.HttpTimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UpstreamFailureClassifierTest {
    @Test void classifies403() { assertEquals("HTTP_403", UpstreamFailureClassifier.classify(new UpstreamException("Upstream returned HTTP 403"))); }
    @Test void classifies429() { assertEquals("HTTP_429", UpstreamFailureClassifier.classify(new UpstreamException("provider returned HTTP 429"))); }
    @Test void classifies521As5xx() { assertEquals("HTTP_5XX", UpstreamFailureClassifier.classify(new UpstreamException("Upstream returned HTTP 521"))); }
    @Test void classifies302AsRedirect() { assertEquals("HTTP_REDIRECT", UpstreamFailureClassifier.classify(new UpstreamException("provider returned HTTP 302"))); }
    @Test void classifiesTlsCertificateMismatchBeforeGenericIo() { assertEquals("TLS_CERTIFICATE", UpstreamFailureClassifier.classify(new UpstreamException("Failed to read URL after 2 attempt(s): (certificate_unknown) No subject alternative DNS name matching ifounder.com found."))); }
    @Test void classifiesBotChallenge() { assertEquals("BOT_CHALLENGE", UpstreamFailureClassifier.classify(new UpstreamException("duckduckgo bot challenge detected"))); }
    @Test void classifiesTimeoutCause() { assertEquals("TIMEOUT", UpstreamFailureClassifier.classify(new UpstreamException("request failed", new HttpTimeoutException("timeout")))); }
    @Test void classifiesParseEmpty() { assertEquals("PARSE_EMPTY", UpstreamFailureClassifier.classify(new UpstreamException("returned no parsable results"))); }
}
