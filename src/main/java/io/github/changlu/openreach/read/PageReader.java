package io.github.changlu.openreach.read;

import io.github.changlu.openreach.read.dto.ReadRequest;
import io.github.changlu.openreach.read.dto.ReadResponse;

public interface PageReader {
    String name();
    ReadResponse read(ReadRequest request);
}
