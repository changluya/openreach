package io.github.changlu.openreach.read.reader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HtmlContentExtractor {
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET_PATTERN = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\\\"']?([^\\s\\\"'/>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSITIVE_HINT = Pattern.compile("article|content|post|entry|main|markdown|docs?|story|text|prose", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE_HINT = Pattern.compile("nav|menu|footer|header|sidebar|aside|promo|banner|hero|advert|cookie|modal|toolbar|breadcrumb", Pattern.CASE_INSENSITIVE);

    public Extraction extract(byte[] body, String baseUri, String contentType, int maxChars) {
        Charset charset = detectCharset(contentType, body);
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/plain")) {
            String text = normalizeText(new String(body, charset));
            boolean truncated = text.length() > maxChars;
            return new Extraction("", truncate(text, maxChars), truncated, Map.of(), List.of());
        }

        Document doc = Jsoup.parse(new String(body, charset), baseUri);
        String title = firstNonBlank(
                attr(doc, "meta[property=og:title]", "content"),
                doc.title().trim()
        );

        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "description", firstNonBlank(
                attr(doc, "meta[name=description]", "content"),
                attr(doc, "meta[property=og:description]", "content")
        ));
        putIfPresent(metadata, "author", firstNonBlank(
                attr(doc, "meta[name=author]", "content"),
                attr(doc, "meta[property=article:author]", "content")
        ));
        putIfPresent(metadata, "publishedAt", firstNonBlank(
                attr(doc, "meta[property=article:published_time]", "content"),
                attr(doc, "time[datetime]", "datetime")
        ));

        Document working = doc.clone();
        working.select("script,style,noscript,nav,footer,header,aside,form,svg,canvas,template,dialog,iframe").remove();
        working.select("[aria-hidden=true], .cookie, .cookie-banner, .advertisement, .ads, .ad, .modal, .popup").remove();

        Element contentRoot = chooseBestContentRoot(working);
        String content = readableText(contentRoot);
        boolean truncated = content.length() > maxChars;
        content = truncate(content, maxChars);

        LinkedHashSet<String> links = new LinkedHashSet<>();
        Element linkRoot = contentRoot == null ? working.body() : contentRoot;
        if (linkRoot != null) {
            for (Element a : linkRoot.select("a[href]")) {
                String href = a.absUrl("href");
                if (!href.isBlank() && (href.startsWith("http://") || href.startsWith("https://"))) {
                    links.add(href);
                    if (links.size() >= 100) break;
                }
            }
        }

        return new Extraction(title, content, truncated, metadata, new ArrayList<>(links));
    }

    Element chooseBestContentRoot(Document doc) {
        List<Element> candidates = new ArrayList<>();
        addCandidate(candidates, doc.body());
        for (Element element : doc.select("article,main,[role=main],#content,#main-content,#article,.content,.article,.article-content,.post,.post-content,.entry-content,.markdown-body,.docs-content,.prose")) {
            addCandidate(candidates, element);
        }

        return candidates.stream()
                .max(Comparator.comparingDouble(this::score))
                .orElse(doc.body());
    }

    double score(Element element) {
        if (element == null) return Double.NEGATIVE_INFINITY;
        String text = normalizeText(element.text());
        int length = text.length();
        if (length == 0) return Double.NEGATIVE_INFINITY;

        int paragraphs = element.select("p").size();
        int headings = element.select("h1,h2,h3,h4,h5,h6").size();
        int listItems = element.select("li").size();
        int punctuation = countPunctuation(text);
        int linkTextLength = element.select("a").stream().mapToInt(a -> normalizeText(a.text()).length()).sum();
        double linkDensity = Math.min(1.0, linkTextLength / (double) Math.max(1, length));

        double score = length
                + Math.min(paragraphs, 30) * 90.0
                + Math.min(headings, 15) * 45.0
                + Math.min(listItems, 40) * 12.0
                + Math.min(punctuation, 120) * 5.0
                - linkDensity * Math.min(length, 5000) * 0.9;

        String tag = element.tagName();
        if ("article".equals(tag)) score += 700;
        if ("main".equals(tag)) score += 350;
        if ("body".equals(tag)) score += 120;

        String hint = (element.id() + " " + element.className()).trim();
        if (POSITIVE_HINT.matcher(hint).find()) score += 300;
        if (NEGATIVE_HINT.matcher(hint).find()) score -= 700;
        if (length < 200) score -= 900;
        if (length < 80) score -= 1200;
        return score;
    }

    private String readableText(Element root) {
        if (root == null) return "";
        String full = normalizeText(root.text());
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (Element block : root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th")) {
            String line = normalizeText(block.text());
            if (!line.isBlank() && line.length() >= 2) lines.add(line);
        }
        String structured = String.join("\n", lines).trim();
        if (structured.length() >= Math.max(120, (int) (full.length() * 0.55))) {
            return structured;
        }
        return full;
    }

    private void addCandidate(List<Element> candidates, Element element) {
        if (element != null && !candidates.contains(element)) candidates.add(element);
    }

    private int countPunctuation(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '，' || c == '！' || c == '？' || c == '.' || c == ',' || c == ';' || c == ':' || c == '；' || c == '：') count++;
        }
        return count;
    }

    private Charset detectCharset(String contentType, byte[] body) {
        Matcher header = CHARSET_PATTERN.matcher(contentType == null ? "" : contentType);
        if (header.find()) return safeCharset(header.group(1));
        int len = Math.min(body.length, 8192);
        String prefix = new String(body, 0, len, StandardCharsets.ISO_8859_1);
        Matcher meta = META_CHARSET_PATTERN.matcher(prefix);
        if (meta.find()) return safeCharset(meta.group(1));
        return StandardCharsets.UTF_8;
    }

    private Charset safeCharset(String name) {
        try {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            if ("gb2312".equals(normalized)) normalized = "GB18030";
            return Charset.forName(normalized);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private String attr(Document doc, String selector, String attr) {
        Element el = doc.selectFirst(selector);
        return el == null ? "" : el.attr(attr).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }

    public record Extraction(
            String title,
            String content,
            boolean truncated,
            Map<String, String> metadata,
            List<String> links
    ) {}
}
