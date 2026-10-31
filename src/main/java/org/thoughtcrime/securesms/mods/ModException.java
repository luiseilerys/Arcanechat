package org.thoughtcrime.securesms.mods;

/**
 * Error produced while validating or installing a community mod.
 *
 * <p>The message is intentionally user-displayable: it is shown verbatim in a Toast/dialog by
 * {@link ModsActivity}.
 */
public class ModException extends Exception {

  private static final long serialVersionUID = 1L;

  public ModException(String message) {
    super(message);
  }

  public ModException(String message, Throwable cause) {
    super(message, cause);
  }
}
