package io.github.changlu.openreach.read;

import io.github.changlu.openreach.observability.UpstreamFailureClassifier;
import io.github.changlu.openreach.read.dto.ReadRequest;
import io.github.changlu.openreach.read.dto.ReadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;

@Service
public class WebReadService {
    private static final Logger upstreamLog = LoggerFactory.getLogger("OPENREACH.UPSTREAM");
    private final PageReader pageReader;

    public WebReadService(PageReader pageReader) {
        this.pageReader = pageReader;
    }

    public ReadResponse read(ReadRequest request) {
        long started = System.nanoTime();
        upstreamLog.info("[OPENREACH-READ] read_start host={} path={} maxChars={}", host(request.url()), path(request.url()), request.maxChars());
        try {
            ReadResponse response = pageReader.read(request);
            upstreamLog.info("[OPENREACH-READ] read_success host={} finalHost={} contentType={} truncated={} latencyMs={}",
                    host(request.url()), host(response.finalUrl()), response.contentType(), response.truncated(), (System.nanoTime() - started) / 1_000_000L);
            return response;
        } catch (RuntimeException ex) {
            upstreamLog.error("[OPENREACH-READ] read_fail host={} type={} latencyMs={} message={}",
                    host(request.url()), UpstreamFailureClassifier.classify(ex), (System.nanoTime() - started) / 1_000_000L, compact(ex.getMessage()));
            throw ex;
        }
    }

    private String host(String raw) {
        try { return URI.create(raw).getHost(); } catch (Exception ex) { return "invalid"; }
    }
    private String path(String raw) {
        try { String p = URI.create(raw).getPath(); return p == null || p.isBlank() ? "/" : p; } catch (Exception ex) { return "invalid"; }
    }
    private String compact(String message) {
        if (message == null || message.isBlank()) return "-";
        String value = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
