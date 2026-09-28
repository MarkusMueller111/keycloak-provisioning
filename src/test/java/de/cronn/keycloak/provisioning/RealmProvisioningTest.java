package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

/** Creating a realm is the one operation that runs before the realm it manages exists. */
class RealmProvisioningTest extends SharedKeycloakTest {

  private static final String NEW_REALM = "created-by-test";

  @AfterEach
  void dropCreatedRealm() {
    adminClient().realms().findAll().stream()
        .map(RealmRepresentation::getRealm)
        .filter(NEW_REALM::equals)
        .forEach(realm -> adminClient().realms().realm(realm).remove());
  }

  @Test
  void createsARealmThatDoesNotExistYet() {
    withNewRealmManager(realmManager -> realmManager.createOrUpdateRealm(realm -> {}));

    assertThat(realmNames()).contains(NEW_REALM);
    assertThat(findRealm().isEnabled()).isTrue();
  }

  @Test
  void appliesTheCustomizerWhenCreating() {
    withNewRealmManager(
        realmManager ->
            realmManager.createOrUpdateRealm(realm -> realm.setDisplayName("Created Realm")));

    assertThat(findRealm().getDisplayName()).isEqualTo("Created Realm");
  }

  @Test
  void updatesAnExistingRealm() {
    withNewRealmManager(realmManager -> realmManager.createOrUpdateRealm(realm -> {}));

    withNewRealmManager(
        realmManager -> realmManager.createOrUpdateRealm(realm -> realm.setDisplayName("Renamed")));

    assertThat(findRealm().getDisplayName()).isEqualTo("Renamed");
  }

  /**
   * The common case when provisioning runs on every start: nothing changed, so nothing is written.
   */
  @Test
  void leavesAnUnchangedRealmAlone() {
    Consumer<RealmRepresentation> customizer = realm -> realm.setDisplayName("Stable");
    withNewRealmManager(realmManager -> realmManager.createOrUpdateRealm(customizer));
    String versionBefore = String.valueOf(findRealm().getDisplayName());

    withNewRealmManager(realmManager -> realmManager.createOrUpdateRealm(customizer));

    assertThat(findRealm().getDisplayName()).isEqualTo(versionBefore);
  }

  @Test
  void reportsWhetherTheRealmExists() {
    withNewRealmManager(realmManager -> assertThat(realmManager.getRealmIfExists()).isEmpty());

    withNewRealmManager(realmManager -> realmManager.createOrUpdateRealm(realm -> {}));

    withNewRealmManager(
        realmManager ->
            assertThat(realmManager.getRealmIfExists())
                .get()
                .extracting(RealmRepresentation::getRealm)
                .isEqualTo(NEW_REALM));
  }

  /** The token is issued before the realm exists, so the manager has to refresh it on creation. */
  @Test
  void canManageTheRealmItJustCreated() {
    withNewRealmManager(
        realmManager -> {
          realmManager.createOrUpdateRealm(realm -> {});

          realmManager.provisionManagedClients(List.of(systemClient("system-after-create")));
        });

    assertThat(adminClient().realm(NEW_REALM).clients().findByClientId("system-after-create"))
        .hasSize(1);
  }

  private void withNewRealmManager(Consumer<RealmManager> action) {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      action.accept(connectionManager.realmManager(NEW_REALM));
    }
  }

  private static ClientRepresentation systemClient(String id) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(id);
    client.setEnabled(true);
    return client;
  }

  private RealmRepresentation findRealm() {
    return adminClient().realm(NEW_REALM).toRepresentation();
  }

  private List<String> realmNames() {
    return adminClient().realms().findAll().stream().map(RealmRepresentation::getRealm).toList();
  }
}
