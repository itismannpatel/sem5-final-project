package com.twofold.backend.http;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * In production the Vite bundle lives in {@code static/} inside the jar. Static
 * files are served by Spring, but client-side routes ({@code /discover},
 * {@code /u/u_1}, ...) do not exist on disk, so a hard load would 404. These
 * mappings forward the React Router paths to {@code index.html}.
 *
 * <p>Bare {@code /api} is not API territory either: Node's gate was
 * {@code url.startsWith('/api/')}, so {@code /api} itself fell through to the
 * host and received the SPA. That behaviour is kept here. During development
 * the Vite server serves the SPA instead and only {@code /api/*} reaches us.
 */
@Controller
public class SpaController {

  /** React Router paths from {@code src/App.tsx}. */
  @GetMapping({"/", "/auth", "/dashboard", "/discover", "/matches", "/sessions",
      "/messages", "/messages/{partnerId}", "/u/{userId}", "/profile", "/pro"})
  public String clientRoute() {
    return "forward:/index.html";
  }

  @RequestMapping("/api")
  public String spa() {
    return "forward:/index.html";
  }
}
