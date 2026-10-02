package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RealmRepresentation;

class AppRealmProvisioningTest extends SharedKeycloakTest {

  private static final String APP_REALM = "app";

  @AfterEach
  void dropAppRealm() {
    adminClient().realms().findAll().stream()
        .map(RealmRepresentation::getRealm)
        .filter(APP_REALM::equals)
        .forEach(realm -> adminClient().realms().realm(realm).remove());
  }

  @Test
  void createsTheRealmWithTheHardenedDefaults() {
    provision(AppRealmConfig.builder(APP_REALM).build());

    RealmRepresentation realm = findRealm();
    assertThat(realm.isEnabled()).isTrue();
    assertThat(realm.isBruteForceProtected()).isTrue();
    assertThat(realm.isEventsEnabled()).isTrue();
    assertThat(realm.isAdminEventsEnabled()).isTrue();
    assertThat(realm.getSslRequired()).isEqualTo("external");
    assertThat(realm.getPasswordPolicy()).isEqualTo(PasswordPolicy.DEFAULT);
    assertThat(realm.getSsoSessionIdleTimeout()).isEqualTo(300);
    assertThat(realm.getSsoSessionMaxLifespan()).isEqualTo(3600);
  }

  @Test
  void reEnablesARealmDisabledOutOfBand() {
    provision(AppRealmConfig.builder(APP_REALM).build());

    RealmRepresentation disabled = findRealm();
    disabled.setEnabled(false);
    adminClient().realm(APP_REALM).update(disabled);
    assertThat(findRealm().isEnabled()).isFalse();

    provision(AppRealmConfig.builder(APP_REALM).build());

    assertThat(findRealm().isEnabled()).isTrue();
  }

  @Test
  void defaultsTheDisplayNameToTheRealmName() {
    provision(AppRealmConfig.builder(APP_REALM).build());

    assertThat(findRealm().getDisplayName()).isEqualTo(APP_REALM);
  }

  @Test
  void appliesTheConfiguredSettings() {
    provision(
        AppRealmConfig.builder(APP_REALM)
            .displayName("Application")
            .sslRequired(SslRequired.NONE)
            .bruteForceProtected(false)
            .sessionIdleTimeout(Duration.ofMinutes(7))
            .sessionMaxLifespan(Duration.ofHours(2))
            .accessTokenLifespan(Duration.ofMinutes(3))
            .build());

    RealmRepresentation realm = findRealm();
    assertThat(realm.getDisplayName()).isEqualTo("Application");
    assertThat(realm.getSslRequired()).isEqualTo("none");
    assertThat(realm.isBruteForceProtected()).isFalse();
    assertThat(realm.getSsoSessionIdleTimeout()).isEqualTo(420);
    assertThat(realm.getSsoSessionMaxLifespan()).isEqualTo(7200);
    assertThat(realm.getAccessTokenLifespan()).isEqualTo(180);
  }

  /** Keycloak has two independent switches, so one flag for both would be a lie. */
  @Test
  void togglesUserAndAdminEventsIndependently() {
    provision(
        AppRealmConfig.builder(APP_REALM).eventsEnabled(false).adminEventsEnabled(true).build());

    RealmRepresentation realm = findRealm();
    assertThat(realm.isEventsEnabled()).isFalse();
    assertThat(realm.isAdminEventsEnabled()).isTrue();
  }

  @Test
  void appliesTheConfiguredEventRetention() {
    provision(
        AppRealmConfig.builder(APP_REALM)
            .eventsExpiration(Duration.ofDays(3))
            .adminEventsExpiration(Duration.ofDays(5))
            .build());

    RealmRepresentation realm = findRealm();
    assertThat(realm.getEventsExpiration()).isEqualTo(Duration.ofDays(3).toSeconds());
    assertThat(realm.getAttributes())
        .containsEntry("adminEventsExpiration", String.valueOf(Duration.ofDays(5).toSeconds()));
  }

  /** The realm SharedKeycloakTest creates declares nothing, so it carries Keycloak's defaults. */
  @Test
  void assumesKeycloaksDefaultAccessTokenLifespan() {
    assertThat(adminClient().realm(REALM).toRepresentation().getAccessTokenLifespan())
        .isEqualTo(AppRealmProvisioner.DEFAULT_ACCESS_TOKEN_LIFESPAN_SECONDS);
  }

  @Test
  void restoresKeycloaksDefaultsWhenSettingsAreNoLongerDeclared() {
    provision(
        AppRealmConfig.builder(APP_REALM)
            .accessTokenLifespan(Duration.ofMinutes(3))
            .eventsExpiration(Duration.ofDays(3))
            .adminEventsExpiration(Duration.ofDays(5))
            .build());

    provision(AppRealmConfig.builder(APP_REALM).build());

    RealmRepresentation realm = findRealm();
    assertThat(realm.getAccessTokenLifespan())
        .isEqualTo(AppRealmProvisioner.DEFAULT_ACCESS_TOKEN_LIFESPAN_SECONDS);
    assertThat(realm.getEventsExpiration()).isNull();
    assertThat(realm.getAttributes()).doesNotContainKey("adminEventsExpiration");
  }

  @Test
  void appliesTheSecurityHeaders() {
    provision(AppRealmConfig.builder(APP_REALM).build());

    assertThat(findRealm().getBrowserSecurityHeaders())
        .containsEntry(SecurityHeader.X_FRAME_OPTIONS.getKeycloakName(), "SAMEORIGIN");
  }

  /** The customizer runs last, so it can reach settings the config does not model. */
  @Test
  void appliesTheRealmCustomizerLast() {
    provision(
        AppRealmConfig.builder(APP_REALM)
            .displayName("Configured")
            .realmCustomizer(
                realm -> {
                  realm.setDisplayName("Customized");
                  realm.setLoginTheme("keycloak");
                })
            .build());

    RealmRepresentation realm = findRealm();
    assertThat(realm.getDisplayName()).isEqualTo("Customized");
    assertThat(realm.getLoginTheme()).isEqualTo("keycloak");
  }

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    AppRealmConfig config = AppRealmConfig.builder(APP_REALM).displayName("Stable").build();
    provision(config);

    withAppRealmManager(
        realmManager ->
            assertSendsNoWrites(() -> new AppRealmProvisioner(realmManager, config).provision()));
  }

  @Test
  void rejectsARealmManagerForADifferentRealm() {
    AppRealmConfig config = AppRealmConfig.builder("other").build();

    withAppRealmManager(
        realmManager ->
            assertThatThrownBy(() -> new AppRealmProvisioner(realmManager, config))
                .isInstanceOf(KeycloakException.class)
                .hasMessageContaining("other")
                .hasMessageContaining(APP_REALM));
  }

  private void provision(AppRealmConfig config) {
    withAppRealmManager(realmManager -> new AppRealmProvisioner(realmManager, config).provision());
  }

  private void withAppRealmManager(Consumer<RealmManager> action) {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      action.accept(connectionManager.realmManager(APP_REALM));
    }
  }

  private RealmRepresentation findRealm() {
    return adminClient().realm(APP_REALM).toRepresentation();
  }
}
