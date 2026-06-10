package dev.ohs.player.bulk;

import org.jspecify.annotations.NonNull;

/** Signals a row-level validation failure whose message is safe to surface to the client. */
public class BulkImportRowException extends RuntimeException {

  private final String clientMessage;

  public BulkImportRowException(@NonNull String message) {
    super(message);
    this.clientMessage = message;
  }

  /** Returns the user-safe error message. Never null. */
  @NonNull
  public String getClientMessage() {
    return clientMessage;
  }
}
