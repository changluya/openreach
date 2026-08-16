package io.github.changlu.openreach.observability;

import io.github.changlu.openreach.common.UpstreamException;
import org.junit.jupiter.api.Test;
import java.net.http.HttpTimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UpstreamFailureClassifierTest {
    @Test void classifies403() { assertEquals("HTTP_403", UpstreamFailureClassifier.classify(new UpstreamException("Upstream returned HTTP 403"))); }
    @Test void classifies429() { assertEquals("HTTP_429", UpstreamFailureClassifier.classify(new UpstreamException("provider returned HTTP 429"))); }
    @Test void classifiesBotChallenge() { assertEquals("BOT_CHALLENGE", UpstreamFailureClassifier.classify(new UpstreamException("duckduckgo bot challenge detected"))); }
    @Test void classifiesTimeoutCause() { assertEquals("TIMEOUT", UpstreamFailureClassifier.classify(new UpstreamException("request failed", new HttpTimeoutException("timeout")))); }
    @Test void classifiesParseEmpty() { assertEquals("PARSE_EMPTY", UpstreamFailureClassifier.classify(new UpstreamException("returned no parsable results"))); }
}
