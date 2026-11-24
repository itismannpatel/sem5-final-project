package com.twofold.backend.http;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Bare {@code /api} is not API territory: Node's gate was
 * {@code url.startsWith('/api/')}, so {@code /api} itself fell through to the
 * host and received the SPA. This keeps that behaviour in Java by forwarding
 * to the built app — which exists in production; during Phase 1 development
 * the Vite server serves the SPA instead and only {@code /api/*} reaches us.
 */
@Controller
public class SpaController {

  @RequestMapping("/api")
  public String spa() {
    return "forward:/index.html";
  }
}
