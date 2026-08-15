package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BingSearchProviderTest {
    @Test
    void parsesOrganicResult() {
        String html = """
                <ol id="b_results"><li class="b_algo"><h2><a href="https://spring.io/projects/spring-boot">Spring Boot</a></h2>
                <div class="b_caption"><p>Spring Boot makes it easy to create stand-alone applications.</p></div></li></ol>
                """;
        var provider = new BingSearchProvider(null, new WebCapabilityProperties());
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("Spring Boot", items.get(0).title());
        assertEquals("https://spring.io/projects/spring-boot", items.get(0).url());
        assertEquals("bing", items.get(0).source());
    }
}
