package io.github.changlu.openreach.imagesearch;

import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;

import java.util.List;

public interface ImageSearchProvider {
    String name();
    List<ImageSearchItem> search(String query, int limit, String region);
}
