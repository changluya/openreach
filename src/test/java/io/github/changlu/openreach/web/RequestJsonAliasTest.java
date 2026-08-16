package io.github.changlu.openreach.web;

import tools.jackson.databind.ObjectMapper;
import io.github.changlu.openreach.read.dto.ReadRequest;
import io.github.changlu.openreach.search.SearchTimeRange;
import io.github.changlu.openreach.search.dto.SearchRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestJsonAliasTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchAcceptsSnakeCaseTimeRangeFromToolAdapters() throws Exception {
        SearchRequest request = objectMapper.readValue("""
                {
                  "query": "AI news",
                  "limit": 20,
                  "provider": "auto",
                  "region": "auto",
                  "time_range": "day"
                }
                """, SearchRequest.class);

        assertEquals("day", request.timeRange());
        assertEquals(SearchTimeRange.DAY, request.effectiveTimeRange());
    }

    @Test
    void searchStillAcceptsCanonicalCamelCaseTimeRange() throws Exception {
        SearchRequest request = objectMapper.readValue("""
                {"query":"AI news","timeRange":"week"}
                """, SearchRequest.class);

        assertEquals("week", request.timeRange());
        assertEquals(SearchTimeRange.WEEK, request.effectiveTimeRange());
    }

    @Test
    void readAcceptsSnakeCaseMaxCharsFromToolAdapters() throws Exception {
        ReadRequest request = objectMapper.readValue("""
                {"url":"https://www.techmeme.com/","max_chars":50000}
                """, ReadRequest.class);

        assertEquals(50000, request.maxChars());
    }

    @Test
    void readStillAcceptsCanonicalCamelCaseMaxChars() throws Exception {
        ReadRequest request = objectMapper.readValue("""
                {"url":"https://www.techmeme.com/","maxChars":30000}
                """, ReadRequest.class);

        assertEquals(30000, request.maxChars());
    }
}
