package com.twofold.backend.http;

/**
 * A deliberate API failure: an HTTP status plus the exact {@code {"error": ...}}
 * message the contract promises. Handlers throw these; {@link ApiExceptionHandler}
 * renders them, so no handler ever writes a response body by hand.
 *
 * <p>Unlike the Node implementation — where a client mistake (bad format, unknown
 * foreign key, duplicate email) fell through to a schema violation and surfaced
 * as a bare 500 — every client error now carries a specific message (DEF-010).
 */
public class ApiException extends RuntimeException {

  private final int status;

  public ApiException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(400, message);
  }

  /** Task 2: no valid session cookie on a write. */
  public static ApiException unauthorized(String message) {
    return new ApiException(401, message);
  }

  /** Task 2: a valid session, but the body names someone else as the actor. */
  public static ApiException forbidden(String message) {
    return new ApiException(403, message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(404, message);
  }

  public static ApiException methodNotAllowed(String message) {
    return new ApiException(405, message);
  }
}
