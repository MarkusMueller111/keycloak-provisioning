package de.cronn.keycloak.provisioning;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * Creates an application realm and keeps its realm-level settings in sync. Deliberately does
 * <em>not</em> provision the realm's content: roles, groups, clients and scopes are the project's
 * own, and their order differs per project, so they are applied by calling {@link RealmManager}
 * afterwards.
 *
 * <p>Must run before anything else touches the realm — {@link RealmManager#createOrUpdateRealm}
 * also refreshes the token, which no other call does.
 */
public class AppRealmProvisioner {

  private final RealmManager realmManager;
  private final AppRealmConfig config;

  public AppRealmProvisioner(RealmManager realmManager, AppRealmConfig config) {
    this.realmManager = Objects.requireNonNull(realmManager);
    this.config = Objects.requireNonNull(config);
    assertTargetRealmMatches(realmManager, config);
  }

  private static void assertTargetRealmMatches(RealmManager realmManager, AppRealmConfig config) {
    if (!realmManager.getRealmName().equals(config.realmName())) {
      throw new KeycloakException(
          "This provisioner is configured for realm '"
              + config.realmName()
              + "', but the RealmManager targets '"
              + realmManager.getRealmName()
              + "'");
    }
  }

  public void provision() {
    realmManager.createOrUpdateRealm(this::configureRealm);
  }

  public RealmManager getRealmManager() {
    return realmManager;
  }

  private void configureRealm(RealmRepresentation realm) {
    realm.setEnabled(true);
    realm.setDisplayName(config.displayName());
    realm.setPasswordPolicy(config.passwordPolicy());
    realm.setSslRequired(config.sslRequired().getKeycloakValue());
    realm.setBruteForceProtected(config.bruteForceProtected());
    realm.setEventsEnabled(config.eventsEnabled());
    realm.setAdminEventsEnabled(config.adminEventsEnabled());

    if (config.eventsExpiration() != null) {
      realm.setEventsExpiration(config.eventsExpiration().toSeconds());
    }
    if (config.adminEventsExpiration() != null) {
      // Admin event retention has no setter of its own; Keycloak keeps it as a realm attribute.
      setAttribute(
          realm,
          "adminEventsExpiration",
          String.valueOf(config.adminEventsExpiration().toSeconds()));
    }
    realm.setSsoSessionIdleTimeout(toSecondsInt(config.sessionIdleTimeout()));
    realm.setSsoSessionMaxLifespan(toSecondsInt(config.sessionMaxLifespan()));
    if (config.accessTokenLifespan() != null) {
      realm.setAccessTokenLifespan(toSecondsInt(config.accessTokenLifespan()));
    }

    SecurityHeader.applyTo(realm, config.securityHeaders());

    config.realmCustomizer().accept(realm);
  }

  private static int toSecondsInt(Duration duration) {
    return Math.toIntExact(duration.toSeconds());
  }

  private static void setAttribute(RealmRepresentation realm, String key, String value) {
    Map<String, String> attributes =
        realm.getAttributes() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(realm.getAttributes());
    attributes.put(key, value);
    realm.setAttributes(attributes);
  }
}
