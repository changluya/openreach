package io.github.changlu.openreach.imagesearch.validation;

import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that an image result currently points to directly downloadable image bytes.
 */
public interface ImageDownloadVerifier {
    boolean isDownloadable(String imageUrl);

    default List<ImageSearchItem> filterDownloadable(List<ImageSearchItem> candidates, int limit) {
        List<ImageSearchItem> accepted = new ArrayList<>();
        if (candidates == null || candidates.isEmpty() || limit <= 0) return accepted;
        for (ImageSearchItem item : candidates) {
            if (item == null || item.imageUrl() == null || item.imageUrl().isBlank()) continue;
            if (isDownloadable(item.imageUrl())) {
                accepted.add(item);
                if (accepted.size() >= limit) break;
            }
        }
        return accepted;
    }
}
