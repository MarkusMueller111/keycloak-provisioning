package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.master.MasterRealmConfig;
import de.cronn.keycloak.provisioning.master.MasterRealmProvisioner;
import de.cronn.keycloak.provisioning.master.PermanentAdmin;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;

/**
 * {@code kc.sh bootstrap-admin} can create a client instead of a user. Keycloak leaves its service
 * account out of the user list, so nothing on the user side can reach it.
 */
class TemporaryAdminClientProvisioningTest extends OwnKeycloakTest {

  @Override
  protected Map<String, String> extraEnvironment() {
    return Map.of(
        "KC_BOOTSTRAP_ADMIN_CLIENT_ID", BOOTSTRAP_CLIENT_ID,
        "KC_BOOTSTRAP_ADMIN_CLIENT_SECRET", BOOTSTRAP_CLIENT_SECRET);
  }

  @Test
  void deletesTheTemporaryAdminClient() {
    withMaster(realmManager -> assertThat(clientIds(realmManager)).contains(BOOTSTRAP_CLIENT_ID));

    provision(true);

    withMaster(
        realmManager -> {
          assertThat(realmManager.getClientsMarkedAsTemporaryAdmin()).isEmpty();
          assertThat(clientIds(realmManager)).doesNotContain(BOOTSTRAP_CLIENT_ID);
          assertThat(realmManager.getUsersMarkedAsTemporaryAdmin()).isEmpty();
        });
  }

  @Test
  void keepsTheTemporaryAdminClientWhenNotAskedToDeleteIt() {
    provision(false);

    withMaster(realmManager -> assertThat(clientIds(realmManager)).contains(BOOTSTRAP_CLIENT_ID));
  }

  private void provision(boolean deleteTemporaryAdmins) {
    MasterRealmConfig config =
        MasterRealmConfig.builder(
                serverUrl(),
                new PermanentAdmin.Standard(
                    "permanent-admin", "Str0ng-P4ssw0rd!", "admin@example.com"))
            .deleteTemporaryAdmins(deleteTemporaryAdmins)
            .builtInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ADMIN_CLI)
                        .enabled(true)
                        .build()))
            .build();
    withMaster(realmManager -> new MasterRealmProvisioner(realmManager, config).provision());
  }

  private static List<String> clientIds(RealmManager realmManager) {
    return realmManager.getRealmResource().clients().findAll().stream()
        .map(ClientRepresentation::getClientId)
        .toList();
  }

  private void withMaster(Consumer<RealmManager> action) {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      action.accept(connectionManager.realmManager("master"));
    }
  }
}
