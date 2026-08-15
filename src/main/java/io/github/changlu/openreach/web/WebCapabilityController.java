package io.github.changlu.openreach.web;

import io.github.changlu.openreach.imagesearch.ImageSearchService;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchRequest;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchResponse;
import io.github.changlu.openreach.read.WebReadService;
import io.github.changlu.openreach.read.dto.ReadRequest;
import io.github.changlu.openreach.read.dto.ReadResponse;
import io.github.changlu.openreach.search.SearchService;
import io.github.changlu.openreach.search.dto.SearchRequest;
import io.github.changlu.openreach.search.dto.SearchResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web")
public class WebCapabilityController {
    private final SearchService searchService;
    private final ImageSearchService imageSearchService;
    private final WebReadService webReadService;

    public WebCapabilityController(SearchService searchService, ImageSearchService imageSearchService, WebReadService webReadService) {
        this.searchService = searchService;
        this.imageSearchService = imageSearchService;
        this.webReadService = webReadService;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return searchService.search(request);
    }

    @PostMapping("/image-search")
    public ImageSearchResponse imageSearch(@Valid @RequestBody ImageSearchRequest request) {
        return imageSearchService.search(request);
    }

    @PostMapping("/read")
    public ReadResponse read(@Valid @RequestBody ReadRequest request) {
        return webReadService.read(request);
    }
}
