package io.github.changlu.openreach;

import io.github.changlu.openreach.common.BoundedBodyReader;
import io.github.changlu.openreach.common.UpstreamException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedBodyReaderTest {

    @Test
    void readsBodyWithinConfiguredLimit() throws Exception {
        byte[] body = "small-response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(body, BoundedBodyReader.read(new ByteArrayInputStream(body), 1024, "test"));
    }

    @Test
    void rejectsBodyBeyondConfiguredLimit() {
        byte[] body = new byte[2048];
        assertThrows(UpstreamException.class,
                () -> BoundedBodyReader.read(new ByteArrayInputStream(body), 1024, "test"));
    }
}
