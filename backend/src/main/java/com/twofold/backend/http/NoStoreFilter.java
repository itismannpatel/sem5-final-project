package com.twofold.backend.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Cache-Control: no-store} on every {@code /api/} response — the header
 * the Node implementation set on each write, now guaranteed centrally so error
 * responses carry it too. The gate mirrors Node's {@code url.startsWith('/api/')}:
 * bare {@code /api} is SPA territory and stays untouched.
 */
@Component
public class NoStoreFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    if (request.getRequestURI().startsWith("/api/")) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
    chain.doFilter(request, response);
  }
}
