package de.cronn.keycloak.provisioning;

import org.apache.commons.lang3.Validate;

/**
 * How this library names the clients it manages. The prefix is a deletion fence: {@link
 * RealmManager#createOrUpdateClients(java.util.List)} removes every client it recognizes as managed
 * that is no longer configured, so a realm has to be provisioned with the same naming for its whole
 * lifetime.
 */
public final class SystemClientNaming {

  public static final String STANDARD_PREFIX = "system-";

  private final String prefix;

  private SystemClientNaming(String prefix) {
    this.prefix = Validate.notBlank(prefix, "prefix must not be blank");
  }

  public static SystemClientNaming standard() {
    return new SystemClientNaming(STANDARD_PREFIX);
  }

  public static SystemClientNaming custom(String prefix) {
    return new SystemClientNaming(prefix);
  }

  public SystemClientId clientId(String clientId) {
    Validate.notBlank(clientId, "clientId must not be blank");
    Validate.isTrue(
        !isManaged(clientId),
        "clientId must be given without the '%s' prefix, but was '%s'",
        prefix,
        clientId);
    return new SystemClientId(clientId, prefix + clientId);
  }

  public boolean isManaged(String clientId) {
    return clientId.startsWith(prefix);
  }
}
