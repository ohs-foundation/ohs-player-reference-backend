package dev.ohs.player.iam;

/** Thrown when an IAM provider operation fails, carrying the upstream HTTP status code. */
public class IamProviderException extends RuntimeException {

  private final int statusCode;

  public IamProviderException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public IamProviderException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }
}
