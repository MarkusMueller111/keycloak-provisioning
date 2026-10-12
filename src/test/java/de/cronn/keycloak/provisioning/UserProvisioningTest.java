package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.Change.Type;
import de.cronn.keycloak.provisioning.testsupport.TestUserProvisioner;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

class UserProvisioningTest extends SharedKeycloakTest {

  private static final String PASSWORD = "Str0ng-P4ssw0rd!";
  private static final String LOGIN_CLIENT = "login";

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
    withTestUserProvisioner(
        p ->
            assertThat(p.provisionUsers(List.of(changed)).changes())
                .extracting(Change::target, Change::name, Change::type)
                .containsExactly(tuple(Target.USER, "alice", Type.UPDATED)));

    UserRepresentation actual = findUser("alice");
    assertThat(actual.isEnabled()).isFalse();
    assertThat(actual.isEmailVerified()).isFalse();
    assertThat(actual.getFirstName()).isEqualTo("Alicia");
    assertThat(actual.getRequiredActions()).containsExactly("UPDATE_PASSWORD");
  }

  @Test
  void restoresKeycloaksDefaultsWhenDeclarationsAreRemoved() {
    UserRepresentation declared = user("alice");
    declared.setFirstName("Alice");
    declared.setEmailVerified(true);
    declared.setRequiredActions(List.of("UPDATE_PASSWORD"));
    withTestUserProvisioner(p -> p.provisionUsers(List.of(declared)));

    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"))));

    UserRepresentation actual = findUser("alice");
    assertThat(actual.getFirstName()).isNull();
    assertThat(actual.isEmailVerified()).isFalse();
    assertThat(actual.getRequiredActions()).isEmpty();
  }

  @Test
  void deletesUsersThatAreNoLongerConfigured() {
    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"), user("bob"))));

    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"))));

    assertThat(usernames()).containsExactly("alice");
  }

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("alice"))));

    withTestUserProvisioner(
        p -> assertChangesNothing(() -> p.provisionUsers(List.of(user("alice")))));
  }

  @Test
  void sendsNoWritesWhenAUserDeclaringNoEnabledFlagIsRerun() {
    UserRepresentation undeclared = user("alice");
    undeclared.setEnabled(null);
    withTestUserProvisioner(p -> p.provisionUsers(List.of(undeclared)));

    withTestUserProvisioner(p -> assertChangesNothing(() -> p.provisionUsers(List.of(undeclared))));
  }

  @Test
  void sendsOnlyThePasswordResetWhenRerunWithAPassword() {
    withTestUserProvisioner(p -> p.provisionUsers(List.of(userWithPassword("alice"))));

    withTestUserProvisioner(
        p ->
            assertThat(
                    assertSendsOnlyWrites(
                            () -> p.provisionUsers(List.of(userWithPassword("alice"))),
                            "PUT /admin/realms/test/users/<id>/reset-password")
                        .changes())
                .extracting(Change::target, Change::name, Change::type)
                .containsExactly(tuple(Target.PASSWORD, "alice", Type.UPDATED)));
  }

  @Test
  void setsAPasswordThatCanBeUsedToLogIn() {
    withTestUserProvisioner(p -> p.provisionUsers(List.of(userWithPassword("alice"))));

    assertThat(logIn("alice", PASSWORD)).isNotBlank();
  }

  @Test
  void setsANewUsersPasswordOnlyThroughItsCreation() {
    withTestUserProvisioner(
        p ->
            assertSendsOnlyWrites(
                () -> p.provisionUsers(List.of(userWithPassword("alice"))),
                "POST /admin/realms/test/users"));
  }

  @Test
  void restoresAPasswordChangedOutOfBand() {
    withTestUserProvisioner(p -> p.provisionUsers(List.of(userWithPassword("alice"))));
    adminClient()
        .realm(REALM)
        .users()
        .get(findUser("alice").getId())
        .resetPassword(password("Ch4nged-P4ssw0rd!"));

    withTestUserProvisioner(p -> p.provisionUsers(List.of(userWithPassword("alice"))));

    assertThat(logIn("alice", PASSWORD)).isNotBlank();
  }

  /** Both reject a password reset to the current value, so a rerun could never re-apply it. */
  @ParameterizedTest
  @ValueSource(strings = {"passwordHistory(3)", "passwordAge(1)"})
  void rejectsPasswordsUnderAPolicyThatForbidsReusingThem(String policy) {
    RealmRepresentation realm = adminClient().realm(REALM).toRepresentation();
    realm.setPasswordPolicy("length(8) and " + policy);
    adminClient().realm(REALM).update(realm);

    assertThatThrownBy(
            () ->
                withTestUserProvisioner(p -> p.provisionUsers(List.of(userWithPassword("alice")))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining(policy.substring(0, policy.indexOf('(')));
    assertThat(usernames()).doesNotContain("alice");

    withTestUserProvisioner(p -> p.provisionUsers(List.of(user("bob"))));
    assertThat(usernames()).contains("bob");
  }

  /** Keycloak's reset-password endpoint ignores the type and stores any value as the password. */
  @Test
  void rejectsCredentialsOtherThanPasswords() {
    UserRepresentation withOtp = user("alice");
    CredentialRepresentation otp = new CredentialRepresentation();
    otp.setType(CredentialRepresentation.TOTP);
    otp.setValue("123456");
    withOtp.setCredentials(List.of(otp));

    assertThatThrownBy(() -> withTestUserProvisioner(p -> p.provisionUsers(List.of(withOtp))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("otp");
    assertThat(usernames()).doesNotContain("alice");
  }

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

  /** Keycloak lower-cases a stored email, so a mixed-case one is never what the realm holds. */
  @Test
  void rejectsAnEmailThatIsNotLowerCase() {
    UserRepresentation mixedCase = user("alice");
    mixedCase.setEmail("Alice@Example.com");

    assertThatThrownBy(() -> withTestUserProvisioner(p -> p.provisionUsers(List.of(mixedCase))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("email")
        .hasMessageContaining("Alice@Example.com");

    assertThat(usernames()).doesNotContain("alice");
  }

  private static UserRepresentation user(String username) {
    UserRepresentation user = new UserRepresentation();
    user.setUsername(username);
    user.setEnabled(true);
    user.setEmail(username + "@example.com");
    return user;
  }

  /** The default user profile requires both names before a user can log in. */
  private static UserRepresentation userWithPassword(String username) {
    UserRepresentation user = user(username);
    user.setFirstName("First");
    user.setLastName("Last");
    user.setCredentials(List.of(password(PASSWORD)));
    return user;
  }

  private String logIn(String username, String password) {
    if (adminClient().realm(REALM).clients().findByClientId(LOGIN_CLIENT).isEmpty()) {
      ClientRepresentation client = new ClientRepresentation();
      client.setClientId(LOGIN_CLIENT);
      client.setPublicClient(true);
      client.setDirectAccessGrantsEnabled(true);
      adminClient().realm(REALM).clients().create(client).close();
    }
    try (Keycloak keycloak =
        KeycloakBuilder.builder()
            .serverUrl(serverUrl())
            .realm(REALM)
            .grantType(OAuth2Constants.PASSWORD)
            .clientId(LOGIN_CLIENT)
            .username(username)
            .password(password)
            .build()) {
      return keycloak.tokenManager().getAccessTokenString();
    }
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
}
