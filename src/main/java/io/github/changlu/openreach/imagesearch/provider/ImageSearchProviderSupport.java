package io.github.changlu.openreach.imagesearch.provider;

import org.jsoup.Jsoup;

import java.net.URI;
import java.util.Locale;

final class ImageSearchProviderSupport {
    private ImageSearchProviderSupport() {}

    static boolean isHttp(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.trim());
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) {
            return false;
        }
    }

    static String host(String value) {
        if (!isHttp(value)) return "";
        try {
            return URI.create(value).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    static String clean(String value) {
        return value == null ? "" : Jsoup.parse(value).text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    static String formatFromUrl(String value) {
        if (value == null) return null;
        String path;
        try { path = URI.create(value).getPath(); }
        catch (Exception ignored) { path = value; }
        if (path == null) return null;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return null;
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.length() > 5) return null;
        return switch (ext) {
            case "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif" -> ext;
            default -> null;
        };
    }

    static Integer intOrNull(Object value) {
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }
}
