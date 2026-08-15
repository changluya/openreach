package io.github.changlu.openreach.read;

import io.github.changlu.openreach.read.reader.HtmlContentExtractor;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HtmlContentExtractorTest {
    private final HtmlContentExtractor extractor = new HtmlContentExtractor();

    @Test
    void extractsArticleAndRemovesNoise() {
        String html = """
                <html><head><title>Hello</title><meta name="description" content="demo"></head>
                <body><nav>menu</nav><article><h1>Title</h1><p>Main content. This is the actual article body with useful details.</p>
                <p>Second paragraph adds enough useful information for an agent.</p><a href="/next">Next</a></article><footer>footer</footer></body></html>
                """;
        var result = extractor.extract(html.getBytes(StandardCharsets.UTF_8), "https://example.com/a", "text/html; charset=UTF-8", 10000);
        assertEquals("Hello", result.title());
        assertTrue(result.content().contains("Main content."));
        assertFalse(result.content().contains("menu"));
        assertEquals("demo", result.metadata().get("description"));
        assertTrue(result.links().contains("https://example.com/next"));
    }

    @Test
    void doesNotChooseTinyMainWhenBodyContainsSubstantiallyMoreContent() {
        String html = """
                <html><head><title>Spring Boot</title></head><body>
                  <main><p>Get ahead. Learn more.</p></main>
                  <section class="project-content">
                    <h1>Spring Boot 4.1.0</h1>
                    <p>Spring Boot makes it easy to create stand-alone, production-grade Spring based Applications that you can just run.</p>
                    <p>It provides starter dependencies, auto configuration, metrics, health checks and externalized configuration.</p>
                    <h2>Getting Started</h2>
                    <p>Use the quickstart guide or build a RESTful web service with Spring Boot.</p>
                  </section>
                </body></html>
                """;
        var result = extractor.extract(html.getBytes(StandardCharsets.UTF_8), "https://spring.io/projects/spring-boot/", "text/html; charset=utf-8", 10000);
        assertTrue(result.content().contains("Spring Boot 4.1.0"));
        assertTrue(result.content().contains("production-grade"));
        assertTrue(result.content().length() > 200);
    }

    @Test
    void honorsGb18030Charset() {
        String html = "<html><head><title>中文页面</title></head><body><article><p>这是一段用于验证中文编码解析的正文内容，应该可以被完整读取。</p></article></body></html>";
        byte[] bytes = html.getBytes(Charset.forName("GB18030"));
        var result = extractor.extract(bytes, "https://example.com/cn", "text/html; charset=gb2312", 10000);
        assertEquals("中文页面", result.title());
        assertTrue(result.content().contains("中文编码解析"));
    }

    @Test
    void truncatesAtRequestedLimit() {
        String html = "<html><body><article><p>" + "a".repeat(5000) + "</p></article></body></html>";
        var result = extractor.extract(html.getBytes(StandardCharsets.UTF_8), "https://example.com", "text/html", 1000);
        assertTrue(result.truncated());
        assertEquals(1000, result.content().length());
    }
}
