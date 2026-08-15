package io.github.changlu.openreach.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Website entry routes.
 *
 * <p>Spring's static resource handler serves concrete files such as
 * {@code /docs/index.html}, but does not guarantee that a directory-style URL
 * such as {@code /docs/} resolves to that index file. Keep these routes
 * explicit so links remain stable when OpenReach is packaged as a Spring Boot
 * executable JAR or deployed in Docker.</p>
 */
@Controller
public class WebsiteController {

    @GetMapping({"/docs", "/docs/"})
    public String docs() {
        return "forward:/docs/index.html";
    }

    @GetMapping({"/community", "/community/"})
    public String community() {
        return "redirect:/docs/#community";
    }
}
