package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

class UserAdministrationTest extends SharedKeycloakTest {

  @Test
  void createsAUserWithItsPassword() {
    UserRepresentation alice = user("alice");
    alice.setCredentials(List.of(password("Secret-123")));

    String id = fromAdministration(users -> users.createUser(alice));

    assertThat(realm().users().get(id).toRepresentation().getUsername()).isEqualTo("alice");
    assertThat(realm().users().get(id).credentials())
        .extracting(CredentialRepresentation::getType)
        .containsExactly(CredentialRepresentation.PASSWORD);
  }

  @Test
  void refusesToCreateAUserThatExists() {
    withAdministration(users -> users.createUser(user("alice")));

    assertThatThrownBy(() -> withAdministration(users -> users.createUser(user("alice"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("'alice' already exists");
  }

  @Test
  void refusesToCreateAUserWithTheEmailOfAnother() {
    UserRepresentation alice = user("alice");
    alice.setEmail("shared@example.com");
    withAdministration(users -> users.createUser(alice));
    UserRepresentation bob = user("bob");
    bob.setEmail("shared@example.com");

    assertThatThrownBy(() -> withAdministration(users -> users.createUser(bob)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("cannot have the email 'shared@example.com'");
  }

  @Test
  void refusesAMixedCaseUsername() {
    assertThatThrownBy(() -> withAdministration(users -> users.createUser(user("Alice"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lower-case");
  }

  @Test
  void refusesDeclaredMemberships() {
    UserRepresentation withGroups = user("alice");
    withGroups.setGroups(List.of("/Acme"));

    assertThatThrownBy(() -> withAdministration(users -> users.createUser(withGroups)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("GroupAdministration");
  }

  @Test
  void updatesAUserMatchedById() {
    String id = fromAdministration(users -> users.createUser(user("alice")));
    UserRepresentation changed = user("alice");
    changed.setEmail("alice@example.com");
    changed.setFirstName("Alice");

    withAdministration(users -> users.updateUser(id, changed));

    withRealmManager(this::validateUsers);
  }

  @Test
  void refusesToRenameAUserOntoAnExistingOne() {
    allowEditingUsernames();
    withAdministration(users -> users.createUser(user("alice")));
    String bob = fromAdministration(users -> users.createUser(user("bob")));

    assertThatThrownBy(() -> withAdministration(users -> users.updateUser(bob, user("alice"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("'alice' already exists");
  }

  @Test
  void refusesToGiveAUserTheEmailOfAnother() {
    UserRepresentation alice = user("alice");
    alice.setEmail("shared@example.com");
    withAdministration(users -> users.createUser(alice));
    String bob = fromAdministration(users -> users.createUser(user("bob")));
    UserRepresentation bobWithAlicesEmail = user("bob");
    bobWithAlicesEmail.setEmail("shared@example.com");

    assertThatThrownBy(() -> withAdministration(users -> users.updateUser(bob, bobWithAlicesEmail)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("cannot have the email 'shared@example.com'");
  }

  @Test
  void resetsAnUndeclaredFieldToTheValueOfANewUser() {
    String id = fromAdministration(users -> users.createUser(user("alice")));
    UserRepresentation withoutEnabled = user("alice");
    withoutEnabled.setEnabled(null);

    withAdministration(users -> users.updateUser(id, withoutEnabled));

    assertThat(realm().users().get(id).toRepresentation().isEnabled()).isFalse();
  }

  @Test
  void refusesCredentialsOnAnUpdate() {
    String id = fromAdministration(users -> users.createUser(user("alice")));
    UserRepresentation withPassword = user("alice");
    withPassword.setCredentials(List.of(password("Secret-123")));

    assertThatThrownBy(() -> withAdministration(users -> users.updateUser(id, withPassword)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resetPassword");
  }

  @Test
  void refusesToUpdateAUserThatDoesNotExist() {
    assertThatThrownBy(
            () -> withAdministration(users -> users.updateUser("no-such-id", user("alice"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("No user with id 'no-such-id'");
  }

  @Test
  void findsAUserByUsername() {
    String id = fromAdministration(users -> users.createUser(user("alice")));

    withAdministration(
        users -> {
          assertThat(users.findUserByUsername("alice"))
              .get()
              .extracting(UserRepresentation::getId)
              .isEqualTo(id);
          assertThat(users.findUserByUsername("ali")).isEmpty();
        });
  }

  @Test
  void deletesAUser() {
    String id = fromAdministration(users -> users.createUser(user("alice")));

    withAdministration(users -> users.deleteUser(id));

    assertThat(realm().users().searchByUsername("alice", true)).isEmpty();
  }

  @Test
  void setsATemporaryPassword() {
    String id = fromAdministration(users -> users.createUser(user("alice")));

    withAdministration(users -> users.resetPassword(id, "Secret-123", true));

    assertThat(realm().users().get(id).credentials())
        .extracting(CredentialRepresentation::getType)
        .containsExactly(CredentialRepresentation.PASSWORD);
    assertThat(realm().users().get(id).toRepresentation().getRequiredActions())
        .containsExactly("UPDATE_PASSWORD");
  }

  @Test
  void refusesToChangeAServiceAccount() {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId("machine");
    client.setPublicClient(false);
    client.setServiceAccountsEnabled(true);
    String clientUuid;
    try (Response response = realm().clients().create(client)) {
      clientUuid = CreatedResponseUtil.getCreatedId(response);
    }
    String serviceAccountId = realm().clients().get(clientUuid).getServiceAccountUser().getId();

    assertThatThrownBy(() -> withAdministration(users -> users.deleteUser(serviceAccountId)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("service account");
  }

  private void allowEditingUsernames() {
    RealmRepresentation realm = realm().toRepresentation();
    realm.setEditUsernameAllowed(true);
    realm().update(realm);
  }

  private static UserRepresentation user(String username) {
    UserRepresentation user = new UserRepresentation();
    user.setUsername(username);
    user.setEnabled(true);
    return user;
  }

  private static CredentialRepresentation password(String value) {
    CredentialRepresentation credential = new CredentialRepresentation();
    credential.setType(CredentialRepresentation.PASSWORD);
    credential.setValue(value);
    credential.setTemporary(false);
    return credential;
  }

  private RealmResource realm() {
    return adminClient().realm(REALM);
  }

  private <T> T fromAdministration(Function<UserAdministration, T> action) {
    Object[] result = new Object[1];
    withRealmManager(rm -> result[0] = action.apply(new UserAdministration(rm)));
    @SuppressWarnings("unchecked")
    T typed = (T) result[0];
    return typed;
  }

  private void withAdministration(Consumer<UserAdministration> action) {
    withRealmManager(rm -> action.accept(new UserAdministration(rm)));
  }
}
