package io.github.changlu.openreach.read.reader;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.read.PageReader;
import io.github.changlu.openreach.read.dto.ReadRequest;
import io.github.changlu.openreach.read.dto.ReadResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class JsoupPageReader implements PageReader {
    private final SafeHttpFetcher fetcher;
    private final HtmlContentExtractor extractor;
    private final WebCapabilityProperties properties;

    public JsoupPageReader(SafeHttpFetcher fetcher,
                           HtmlContentExtractor extractor,
                           WebCapabilityProperties properties) {
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "jsoup";
    }

    @Override
    public ReadResponse read(ReadRequest request) {
        long started = System.nanoTime();
        int maxChars = request.maxChars() == null
                ? properties.getRead().getMaxChars()
                : Math.min(request.maxChars(), 200_000);

        SafeHttpFetcher.FetchedPage page = fetcher.fetch(request.url());
        HtmlContentExtractor.Extraction extraction = extractor.extract(
                page.body(), page.finalUrl(), page.contentType(), maxChars
        );

        return new ReadResponse(
                request.url(),
                page.finalUrl(),
                extraction.title(),
                extraction.content(),
                page.contentType(),
                name(),
                extraction.truncated(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                extraction.metadata(),
                extraction.links()
        );
    }
}
