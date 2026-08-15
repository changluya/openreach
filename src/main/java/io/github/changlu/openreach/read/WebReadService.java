package io.github.changlu.openreach.read;

import io.github.changlu.openreach.read.dto.ReadRequest;
import io.github.changlu.openreach.read.dto.ReadResponse;
import org.springframework.stereotype.Service;

@Service
public class WebReadService {
    private final PageReader pageReader;

    public WebReadService(PageReader pageReader) {
        this.pageReader = pageReader;
    }

    public ReadResponse read(ReadRequest request) {
        return pageReader.read(request);
    }
}
