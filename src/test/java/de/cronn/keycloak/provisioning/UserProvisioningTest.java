package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.testsupport.TestUserProvisioner;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

class UserProvisioningTest extends SharedKeycloakTest {

  @Test
  void createsTheConfiguredUsers() {
    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"), user("bob"))));

    assertThat(usernames()).contains("alice", "bob");
  }

  @Test
  void provisionsUserFieldsOnUpdate() {
    UserRepresentation initial = user("alice");
    initial.setEnabled(true);
    initial.setEmailVerified(true);
    initial.setFirstName("Alice");
    withTestUserProvisioner(p -> p.provisionUsers(List.of(initial)));

    UserRepresentation changed = user("alice");
    changed.setEnabled(false);
    changed.setEmailVerified(false);
    changed.setFirstName("Alicia");
    changed.setRequiredActions(List.of("UPDATE_PASSWORD"));
    withTestUserProvisioner(p -> p.provisionUsers(List.of(changed)));

    UserRepresentation actual = findUser("alice");
    assertThat(actual.isEnabled()).isFalse();
    assertThat(actual.isEmailVerified()).isFalse();
    assertThat(actual.getFirstName()).isEqualTo("Alicia");
    assertThat(actual.getRequiredActions()).containsExactly("UPDATE_PASSWORD");
  }

  @Test
  void deletesUsersThatAreNoLongerConfigured() {
    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"), user("bob"))));

    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"))));

    assertThat(usernames()).containsExactly("alice");
  }

  @Test
  void isIdempotent() {
    UserRepresentation alice = user("alice");
    withTestUserProvisioner(p -> p.provisionUsers(List.of(alice)));

    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"))));

    assertThat(usernames()).containsOnlyOnce("alice");
  }

  /** Keycloak never returns a credential value, so it is applied rather than diffed. */
  @Test
  void setsAPasswordThatCanBeUsedToLogIn() {
    UserRepresentation alice = user("alice");
    alice.setCredentials(List.of(password("Str0ng-P4ssw0rd!")));

    withTestUserProvisioner(p -> p.provisionUsers(List.of(alice)));

    assertThat(credentialTypes("alice")).contains(CredentialRepresentation.PASSWORD);
  }

  /**
   * Keycloak lower-cases the stored name, so provisioning would compare the configured spelling
   * against it, take the user for unmanaged and delete the account it had just created.
   */
  @Test
  void rejectsAMixedCaseUsername() {
    assertThatThrownBy(() -> withTestUserProvisioner(p -> p.provisionUsers(List.of(user("Alice")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lower-case")
        .hasMessageContaining("Alice");

    assertThat(usernames()).doesNotContain("alice", "Alice");
  }

  /** Keycloak leaves service accounts out of the user listing, which is what protects them. */
  @Test
  void leavesServiceAccountUsersAlone() {
    ClientRepresentation serviceAccountClient = new ClientRepresentation();
    serviceAccountClient.setClientId("system-with-service-account");
    serviceAccountClient.setEnabled(true);
    serviceAccountClient.setPublicClient(false);
    serviceAccountClient.setServiceAccountsEnabled(true);
    withRealmManager(rm -> rm.provisionManagedClients(List.of(serviceAccountClient)));
    String serviceAccountId = serviceAccountUserId();

    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"))));

    assertThat(serviceAccountUserId()).isEqualTo(serviceAccountId);
  }

  private String serviceAccountUserId() {
    String id =
        adminClient()
            .realm(REALM)
            .clients()
            .findByClientId("system-with-service-account")
            .getFirst()
            .getId();
    return adminClient().realm(REALM).clients().get(id).getServiceAccountUser().getId();
  }

  /** Keycloak ignores these on creation and this provisioner applies none of them. */
  @Test
  void rejectsAUserDeclaringRolesOrGroups() {
    UserRepresentation withRoles = user("alice");
    withRoles.setRealmRoles(List.of("some-role"));

    assertThatThrownBy(() -> withTestUserProvisioner(p -> p.provisionUsers(List.of(withRoles))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("realmRoles");

    UserRepresentation withGroups = user("bob");
    withGroups.setGroups(List.of("some-group"));

    assertThatThrownBy(() -> withTestUserProvisioner(p -> p.provisionUsers(List.of(withGroups))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("groups");

    assertThat(usernames()).doesNotContain("alice", "bob");
  }

  private void withTestUserProvisioner(Consumer<TestUserProvisioner> action) {
    withRealmManager(realmManager -> action.accept(new TestUserProvisioner(realmManager)));
  }

  private static UserRepresentation user(String username) {
    UserRepresentation user = new UserRepresentation();
    user.setUsername(username);
    user.setEnabled(true);
    user.setEmail(username + "@example.com");
    return user;
  }

  private static CredentialRepresentation password(String value) {
    CredentialRepresentation credential = new CredentialRepresentation();
    credential.setType(CredentialRepresentation.PASSWORD);
    credential.setValue(value);
    credential.setTemporary(false);
    return credential;
  }

  private List<String> usernames() {
    return adminClient().realm(REALM).users().list(null, Integer.MAX_VALUE).stream()
        .map(UserRepresentation::getUsername)
        .toList();
  }

  private UserRepresentation findUser(String username) {
    return adminClient().realm(REALM).users().searchByUsername(username, true).getFirst();
  }

  private List<String> credentialTypes(String username) {
    return adminClient().realm(REALM).users().get(findUser(username).getId()).credentials().stream()
        .map(CredentialRepresentation::getType)
        .toList();
  }
}
