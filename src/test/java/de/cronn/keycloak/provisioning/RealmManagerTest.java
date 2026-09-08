package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

class RealmManagerTest extends SharedKeycloakTest {

  @Test
  void findsAUserResourceByUsername() {
    createUser("alice");

    withRealmManager(
        realmManager -> {
          assertThat(realmManager.getUserResourceByUsername("alice"))
              .get()
              .extracting(userResource -> userResource.toRepresentation().getUsername())
              .isEqualTo("alice");
          assertThat(realmManager.getUserResourceByUsername("nobody")).isEmpty();
        });
  }

  @Test
  void findsAUserByUsername() {
    createUser("bob");

    withRealmManager(
        realmManager -> {
          assertThat(realmManager.getUserByUsername("bob"))
              .get()
              .extracting(UserRepresentation::getUsername)
              .isEqualTo("bob");
          assertThat(realmManager.getUserByUsername("nobody")).isEmpty();
        });
  }

  /** Exact matching: a prefix of an existing username finds nothing. */
  @Test
  void searchesUsersByExactUsername() {
    createUser("carol");
    createUser("carol-two");

    withRealmManager(
        realmManager -> {
          assertThat(realmManager.searchUsersByExactUsername("carol"))
              .extracting(UserRepresentation::getUsername)
              .containsExactly("carol");
          assertThat(realmManager.searchUsersByExactUsername("caro")).isEmpty();
        });
  }

  @Test
  void listsUsersUpToTheGivenMaximum() {
    createUser("dave");
    createUser("erin");
    createUser("frank");

    withRealmManager(
        realmManager -> {
          assertThat(realmManager.getUsers(2)).hasSize(2);
          assertThat(realmManager.getUsers(100))
              .extracting(UserRepresentation::getUsername)
              .contains("dave", "erin", "frank");
        });
  }

  @Test
  void listsRealmRolesSortedByName() {
    createRealmRole("zeta-role");
    createRealmRole("alpha-role");

    withRealmManager(
        realmManager -> {
          List<String> names =
              realmManager.getRoles().stream().map(RoleRepresentation::getName).toList();

          assertThat(names).contains("alpha-role", "zeta-role");
          assertThat(names).isSorted();
        });
  }

  @Test
  void grantsRealmRolesToAUser() {
    createUser("grace");
    createRealmRole("granted-role");

    withRealmManager(
        realmManager -> {
          realmManager.addRealmRoles(
              realmManager.getUserResourceByUsername("grace").orElseThrow(),
              List.of("granted-role"),
              "grace");

          assertThat(realmRoleNames(realmManager, "grace")).contains("granted-role");
        });
  }

  @Test
  void grantsNothingForAnEmptyRoleList() {
    createUser("heidi");

    withRealmManager(
        realmManager -> {
          realmManager.addRealmRoles(
              realmManager.getUserResourceByUsername("heidi").orElseThrow(), List.of(), "heidi");

          assertThat(realmRoleNames(realmManager, "heidi")).containsExactly("default-roles-test");
        });
  }

  /** Reaches a service account's realm roles, which is how the provisioning client gets admin. */
  @Test
  void reachesTheRoleScopeOfAServiceAccount() {
    createRealmRole("scoped-role");
    createServiceAccountClient("system-scoped");

    withRealmManager(
        realmManager -> {
          RoleRepresentation role =
              realmManager.getRealmResource().roles().get("scoped-role").toRepresentation();
          realmManager.getServiceAccountRealmRoleScope("system-scoped").add(List.of(role));

          assertThat(realmManager.getServiceAccountRealmRoleScope("system-scoped").listAll())
              .extracting(RoleRepresentation::getName)
              .contains("scoped-role");
        });
  }

  private List<String> realmRoleNames(RealmManager realmManager, String username) {
    return realmManager
        .getUserResourceByUsername(username)
        .orElseThrow()
        .roles()
        .realmLevel()
        .listAll()
        .stream()
        .map(RoleRepresentation::getName)
        .toList();
  }

  private void createUser(String username) {
    UserRepresentation user = new UserRepresentation();
    user.setUsername(username);
    user.setEnabled(true);
    try (Response response = adminClient().realm(REALM).users().create(user)) {
      assertThat(response.getStatus()).isEqualTo(201);
    }
  }

  private void createRealmRole(String name) {
    RoleRepresentation role = new RoleRepresentation();
    role.setName(name);
    adminClient().realm(REALM).roles().create(role);
  }

  private void createServiceAccountClient(String clientId) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(clientId);
    client.setEnabled(true);
    client.setServiceAccountsEnabled(true);
    client.setPublicClient(false);
    try (Response response = adminClient().realm(REALM).clients().create(client)) {
      assertThat(response.getStatus()).isEqualTo(201);
    }
  }
}
