package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCliConnectionManager;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.testcontainers.containers.GenericContainer;

/**
 * For tests whose effects are confined to a realm of their own, which is dropped again afterwards.
 * They share one container for the whole suite, so they must not change the master realm beyond
 * bootstrapping the provisioning client.
 */
abstract class SharedKeycloakTest extends KeycloakContainerSupport {

  private static final GenericContainer<?> KEYCLOAK = startKeycloak();

  /**
   * Shared like the container: an admin-cli token carries no roles, the admin endpoints resolve the
   * session's user per request, so it stays valid across the realms these tests create and drop.
   */
  private static final KeycloakAdminCliConnectionManager ADMIN_CONNECTION =
      adminConnection(KEYCLOAK);

  protected static final String REALM = "test";

  @Override
  protected GenericContainer<?> keycloak() {
    return KEYCLOAK;
  }

  @Override
  protected Keycloak adminClient() {
    return ADMIN_CONNECTION.getKeycloakClient();
  }

  @BeforeEach
  void createRealm() {
    RealmRepresentation realm = new RealmRepresentation();
    realm.setRealm(REALM);
    realm.setEnabled(true);
    adminClient().realms().create(realm);
  }

  @AfterEach
  void dropRealm() {
    adminClient().realm(REALM).remove();
  }

  /**
   * The connection has to be opened after the realm exists: its token is issued at connect time and
   * does not cover realms created afterwards.
   */
  protected void withRealmManager(Consumer<RealmManager> action) {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      action.accept(connectionManager.realmManager(REALM));
    }
  }
}
