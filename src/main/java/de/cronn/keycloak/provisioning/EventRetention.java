package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.differ.Declared;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.keycloak.representations.idm.RealmRepresentation;

/** How long a realm keeps its login and admin events; {@link Duration#ZERO} keeps them forever. */
public final class EventRetention {

  public static final Duration DEFAULT_EVENTS_EXPIRATION = Duration.ofDays(90);
  public static final Duration DEFAULT_ADMIN_EVENTS_EXPIRATION = Duration.ofDays(7);

  private static final String ADMIN_EVENTS_EXPIRATION = "adminEventsExpiration";

  private EventRetention() {}

  public static void applyTo(
      RealmRepresentation realm, Duration eventsExpiration, Duration adminEventsExpiration) {
    // Keycloak stores 0 as no expiry and returns it as null.
    Long events = Declared.orDefault(eventsExpiration.toSeconds(), realm.getEventsExpiration(), 0L);
    if (events != null) {
      realm.setEventsExpiration(events);
    }
    // Admin event retention has no setter of its own; Keycloak keeps it as a realm attribute, and
    // drops an attribute left out of the update.
    setAttribute(
        realm,
        ADMIN_EVENTS_EXPIRATION,
        adminEventsExpiration.isZero() ? null : String.valueOf(adminEventsExpiration.toSeconds()));
  }

  /** {@code null} removes the attribute. */
  private static void setAttribute(RealmRepresentation realm, String key, String value) {
    Map<String, String> current = realm.getAttributes() == null ? Map.of() : realm.getAttributes();
    if (Objects.equals(current.get(key), value)) {
      return;
    }
    Map<String, String> attributes = new LinkedHashMap<>(current);
    if (value == null) {
      attributes.remove(key);
    } else {
      attributes.put(key, value);
    }
    realm.setAttributes(attributes);
  }
}
