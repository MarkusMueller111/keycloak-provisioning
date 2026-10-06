package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.differ.Declared;
import java.time.Duration;
import java.util.Objects;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * Creates an application realm, keeps its realm-level settings in sync and hardens the clients
 * Keycloak provides. Deliberately does <em>not</em> provision the realm's own content: roles,
 * groups, clients and scopes are the project's own, and their order differs per project, so they
 * are applied by calling {@link RealmManager} afterwards.
 *
 * <p>Must run before anything else touches the realm: creating it also refreshes the access token,
 * which was issued before the realm existed and does not cover it yet.
 */
public class AppRealmProvisioner {

  static final int DEFAULT_ACCESS_TOKEN_LIFESPAN_SECONDS = 300;

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

  public ProvisioningResult provision() {
    // admin-cli enables a password grant that bypasses the browser flow and any 2FA in it, which
    // is worth closing in an application realm as much as in master.
    return realmManager
        .createOrUpdateRealm(this::configureRealm)
        .plus(realmManager.provisionBuiltInClients(config.builtInClients()));
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
    EventRetention.applyTo(realm, config.eventsExpiration(), config.adminEventsExpiration());
    realm.setSsoSessionIdleTimeout(toSecondsInt(config.sessionIdleTimeout()));
    realm.setSsoSessionMaxLifespan(toSecondsInt(config.sessionMaxLifespan()));
    realm.setAccessTokenLifespan(
        Declared.orDefault(
            config.accessTokenLifespan() == null
                ? null
                : toSecondsInt(config.accessTokenLifespan()),
            realm.getAccessTokenLifespan(),
            DEFAULT_ACCESS_TOKEN_LIFESPAN_SECONDS));

    SecurityHeader.applyTo(realm, config.securityHeaders());

    config.realmCustomizer().accept(realm);
  }

  private static int toSecondsInt(Duration duration) {
    return Math.toIntExact(duration.toSeconds());
  }
}
