package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
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
    assertThat(realm.getEventsExpiration()).isEqualTo(Duration.ofDays(90).toSeconds());
    assertThat(realm.getAttributes())
        .containsEntry("adminEventsExpiration", String.valueOf(Duration.ofDays(7).toSeconds()));
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

  /** admin-cli's password grant bypasses the browser flow, so an app realm closes it too. */
  @Test
  void disablesTheSameBuiltInClientsAsTheMasterRealm() {
    provision(AppRealmConfig.builder(APP_REALM).build());

    assertThat(appClient("admin-cli").isEnabled()).isFalse();
    assertThat(appClient("broker").isEnabled()).isFalse();
    assertThat(appClient("account").isStandardFlowEnabled()).isFalse();
  }

  /** The same guards as the master realm: silently collapsing a duplicate would hide a mistake. */
  @Test
  void rejectsTheBuiltInClientsTheMasterRealmRejects() {
    assertThatThrownBy(
            () ->
                AppRealmConfig.builder(APP_REALM)
                    .builtInClients(
                        List.of(
                            BuiltInClientConfig.forClient(KeycloakProvidedClient.BROKER).build(),
                            BuiltInClientConfig.forClient(KeycloakProvidedClient.BROKER).build()))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than once");

    assertThatThrownBy(
            () ->
                AppRealmConfig.builder(APP_REALM)
                    .builtInClients(
                        List.of(
                            BuiltInClientConfig.forClient(KeycloakProvidedClient.REALM_MANAGEMENT)
                                .enabled(false)
                                .build()))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realm-management");
  }

  @Test
  void letsAnAppRealmKeepABuiltInClient() {
    provision(
        AppRealmConfig.builder(APP_REALM)
            .builtInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ADMIN_CLI)
                        .enabled(true)
                        .build()))
            .build());

    assertThat(appClient("admin-cli").isEnabled()).isTrue();
    assertThat(appClient("broker").isEnabled()).isFalse();
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
  void keepsEventsForeverWhenTheExpirationIsZero() {
    provision(
        AppRealmConfig.builder(APP_REALM)
            .eventsExpiration(Duration.ZERO)
            .adminEventsExpiration(Duration.ZERO)
            .build());

    RealmRepresentation realm = findRealm();
    assertThat(realm.getEventsExpiration()).isNull();
    assertThat(realm.getAttributes()).doesNotContainKey("adminEventsExpiration");
  }

  @Test
  void restoresTheDefaultsWhenSettingsAreNoLongerDeclared() {
    provision(
        AppRealmConfig.builder(APP_REALM)
            .accessTokenLifespan(Duration.ofMinutes(3))
            .eventsExpiration(Duration.ofDays(3))
            .adminEventsExpiration(Duration.ofDays(5))
            .securityHeaders(Map.of(SecurityHeader.X_FRAME_OPTIONS, "DENY"))
            .build());

    provision(AppRealmConfig.builder(APP_REALM).build());

    RealmRepresentation realm = findRealm();
    assertThat(realm.getAccessTokenLifespan())
        .isEqualTo(AppRealmProvisioner.DEFAULT_ACCESS_TOKEN_LIFESPAN_SECONDS);
    assertThat(realm.getEventsExpiration()).isEqualTo(Duration.ofDays(90).toSeconds());
    assertThat(realm.getAttributes())
        .containsEntry("adminEventsExpiration", String.valueOf(Duration.ofDays(7).toSeconds()));
    assertThat(realm.getBrowserSecurityHeaders())
        .containsEntry(SecurityHeader.X_FRAME_OPTIONS.getKeycloakName(), "SAMEORIGIN");
  }

  @Test
  void mergesDeclaredSecurityHeadersOverTheDefaults() {
    provision(
        AppRealmConfig.builder(APP_REALM)
            .securityHeaders(Map.of(SecurityHeader.X_FRAME_OPTIONS, "DENY"))
            .build());

    assertThat(findRealm().getBrowserSecurityHeaders())
        .containsEntry(SecurityHeader.X_FRAME_OPTIONS.getKeycloakName(), "DENY")
        .containsEntry(
            SecurityHeader.CONTENT_SECURITY_POLICY.getKeycloakName(),
            SecurityHeader.DEFAULT_VALUES.get(SecurityHeader.CONTENT_SECURITY_POLICY));
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
            assertChangesNothing(() -> new AppRealmProvisioner(realmManager, config).provision()));
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

  private ClientRepresentation appClient(String clientId) {
    return adminClient().realm(APP_REALM).clients().findByClientId(clientId).getFirst();
  }
}
