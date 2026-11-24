package com.twofold.backend.http;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.twofold.backend.payments.PaymentFailedException;

/**
 * Renders every API failure as the contract's {@code {"error": ...}} body with
 * the right status, {@code Content-Type: application/json; charset=utf-8} and
 * {@code Cache-Control: no-store} — the exact headers the Node implementation
 * sent.
 *
 * <p>Unexpected exceptions are logged server-side and reported as a bare
 * {@code Internal server error}: a 500 must never blank the page, the client
 * keeps its last good snapshot (that is what {@code src/data/store.ts} does
 * with any non-2xx response).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Deliberate API failure: status + message, no logging (it is control flow). */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, String>> deliberate(ApiException e) {
    return body(e.status(), e.getMessage());
  }

  /**
   * D4: a refused sandbox charge. The gateway threw
   * {@link PaymentFailedException} <em>before</em> any state change, so this
   * is a clean 402 with the contract body — and no version bump.
   */
  @ExceptionHandler(PaymentFailedException.class)
  public ResponseEntity<Map<String, String>> paymentFailed(PaymentFailedException e) {
    return body(402, e.getMessage());
  }

  /** Anything else: log it for the defect trail, tell the client nothing. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> unexpected(Exception e) {
    if (e instanceof ErrorResponse spring) {
      // Spring's own signalling (missing static asset, bad method on a
      // non-API path) — keep its status, but speak the contract's dialect.
      int status = spring.getStatusCode().value();
      if (status == 404) {
        return body(404, "Not found");
      }
      if (status == 405) {
        return body(405, "Method not allowed");
      }
      if (status >= 400 && status < 500) {
        return body(status, "Bad request");
      }
      return body(status, "Internal server error");
    }
    log.error("[twofold api] unhandled error", e);
    return body(500, "Internal server error");
  }

  private static ResponseEntity<Map<String, String>> body(int status, String message) {
    return ResponseEntity.status(status)
        .contentType(MediaType.parseMediaType("application/json; charset=utf-8"))
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(Map.of("error", message));
  }
}
