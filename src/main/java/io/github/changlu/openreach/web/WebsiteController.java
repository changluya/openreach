package io.github.changlu.openreach.web;

import io.github.changlu.openreach.security.MonitorAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Website and internal monitor entry routes.
 */
@Controller
public class WebsiteController {
    private final MonitorAuthService monitorAuthService;

    public WebsiteController(MonitorAuthService monitorAuthService) {
        this.monitorAuthService = monitorAuthService;
    }

    @GetMapping({"/docs", "/docs/"})
    public String docs() {
        return "forward:/docs/index.html";
    }

    @GetMapping({"/changelog", "/changelog/"})
    public String changelog() {
        return "forward:/changelog.html";
    }

    @GetMapping({"/monitor", "/monitor/"})
    public String monitor(HttpServletRequest request) {
        if (!monitorAuthService.isAuthenticated(request)) {
            return "redirect:/monitor/login";
        }
        return "forward:/monitor.html";
    }

    @GetMapping({"/monitor/login", "/monitor/login/"})
    public String monitorLoginPage(HttpServletRequest request) {
        if (monitorAuthService.isAuthenticated(request)) {
            return "redirect:/monitor";
        }
        return "forward:/monitor-login.html";
    }

    @PostMapping("/monitor/login")
    public String monitorLogin(
            @RequestParam(name = "username", defaultValue = "") String username,
            @RequestParam(name = "password", defaultValue = "") String password,
            HttpServletRequest request) {
        if (monitorAuthService.authenticate(request, username, password)) {
            return "redirect:/monitor";
        }
        return "redirect:/monitor/login?error=1";
    }

    @PostMapping("/monitor/logout")
    public String monitorLogout(HttpServletRequest request) {
        monitorAuthService.logout(request);
        return "redirect:/monitor/login?logout=1";
    }

    @GetMapping({"/community", "/community/"})
    public String community() {
        return "redirect:/docs/#community";
    }
}
