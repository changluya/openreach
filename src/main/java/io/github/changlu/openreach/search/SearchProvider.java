package io.github.changlu.openreach.search;

import io.github.changlu.openreach.search.dto.SearchItem;

import java.util.List;

public interface SearchProvider {
    String name();
    List<SearchItem> search(String query, int limit, String region);
}
