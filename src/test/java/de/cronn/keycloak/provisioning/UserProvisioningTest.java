package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.Change.Type;
import java.util.List;
import java.util.stream.IntStream;
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
    withRealmManager(
        p -> p.provisionManagedUsers(List.of(user("system-alice"), user("system-bob"))));

    assertThat(usernames()).contains("system-alice", "system-bob");
  }

  @Test
  void provisionsUserFieldsOnUpdate() {
    UserRepresentation initial = user("system-alice");
    initial.setEnabled(true);
    initial.setEmailVerified(true);
    initial.setFirstName("Alice");
    withRealmManager(p -> p.provisionManagedUsers(List.of(initial)));

    UserRepresentation changed = user("system-alice");
    changed.setEnabled(false);
    changed.setEmailVerified(false);
    changed.setFirstName("Alicia");
    changed.setRequiredActions(List.of("UPDATE_PASSWORD"));
    withRealmManager(
        p ->
            assertThat(p.provisionManagedUsers(List.of(changed)).changes())
                .extracting(Change::target, Change::name, Change::type)
                .containsExactly(tuple(Target.USER, "system-alice", Type.UPDATED)));

    withRealmManager(this::validateUsers);
  }

  @Test
  void restoresKeycloaksDefaultsWhenDeclarationsAreRemoved() {
    UserRepresentation declared = user("system-alice");
    declared.setFirstName("Alice");
    declared.setEmailVerified(true);
    declared.setRequiredActions(List.of("UPDATE_PASSWORD"));
    withRealmManager(p -> p.provisionManagedUsers(List.of(declared)));

    withRealmManager(p -> p.provisionManagedUsers(List.of(user("system-alice"))));

    withRealmManager(this::validateUsers);
  }

  @Test
  void deletesUsersThatAreNoLongerConfigured() {
    withRealmManager(
        p -> p.provisionManagedUsers(List.of(user("system-alice"), user("system-bob"))));

    withRealmManager(p -> p.provisionManagedUsers(List.of(user("system-alice"))));

    assertThat(usernames()).containsExactly("system-alice");
  }

  @Test
  void leavesUsersWithoutThePrefixAlone() {
    UserRepresentation unmanaged = user("carol");
    adminClient().realm(REALM).users().create(unmanaged).close();

    withRealmManager(p -> p.provisionManagedUsers(List.of(user("system-alice"))));

    assertThat(usernames()).containsExactlyInAnyOrder("carol", "system-alice");
  }

  @Test
  void leavesAUserAloneWhoseOtherFieldsStartWithThePrefix() {
    UserRepresentation dave = user("dave");
    dave.setFirstName("system-dave");
    dave.setEmail("system-dave@example.com");
    adminClient().realm(REALM).users().create(dave).close();

    withRealmManager(p -> p.provisionManagedUsers(List.of()));

    assertThat(usernames()).containsExactly("dave");
  }

  @Test
  void seesManagedUsersBeyondTheFirstPage() {
    List<UserRepresentation> users =
        IntStream.rangeClosed(1, 101).mapToObj(i -> user("system-user-%03d".formatted(i))).toList();
    withRealmManager(p -> p.provisionManagedUsers(users));

    withRealmManager(p -> assertChangesNothing(() -> p.provisionManagedUsers(users)));
  }

  @Test
  void rejectsAUserWithoutThePrefix() {
    assertThatThrownBy(() -> withRealmManager(p -> p.provisionManagedUsers(List.of(user("carol")))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("carol")
        .hasMessageContaining("system-");

    assertThat(usernames()).doesNotContain("carol");
  }

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    withRealmManager(p -> p.provisionManagedUsers(List.of(user("system-alice"))));

    withRealmManager(
        p -> assertChangesNothing(() -> p.provisionManagedUsers(List.of(user("system-alice")))));
  }

  @Test
  void sendsNoWritesWhenAUserDeclaringNoEnabledFlagIsRerun() {
    UserRepresentation undeclared = user("system-alice");
    undeclared.setEnabled(null);
    withRealmManager(p -> p.provisionManagedUsers(List.of(undeclared)));

    withRealmManager(p -> assertChangesNothing(() -> p.provisionManagedUsers(List.of(undeclared))));
  }

  @Test
  void sendsOnlyThePasswordResetWhenRerunWithAPassword() {
    withRealmManager(p -> p.provisionManagedUsers(List.of(userWithPassword("system-alice"))));

    withRealmManager(
        p ->
            assertThat(
                    assertSendsOnlyWrites(
                            () ->
                                p.provisionManagedUsers(List.of(userWithPassword("system-alice"))),
                            "PUT /admin/realms/test/users/<id>/reset-password")
                        .changes())
                .extracting(Change::target, Change::name, Change::type)
                .containsExactly(tuple(Target.PASSWORD, "system-alice", Type.UPDATED)));
  }

  @Test
  void setsAPasswordThatCanBeUsedToLogIn() {
    withRealmManager(p -> p.provisionManagedUsers(List.of(userWithPassword("system-alice"))));

    assertThat(logIn("system-alice", PASSWORD)).isNotBlank();
  }

  @Test
  void setsANewUsersPasswordOnlyThroughItsCreation() {
    withRealmManager(
        p ->
            assertSendsOnlyWrites(
                () -> p.provisionManagedUsers(List.of(userWithPassword("system-alice"))),
                "POST /admin/realms/test/users"));
  }

  @Test
  void restoresAPasswordChangedOutOfBand() {
    withRealmManager(p -> p.provisionManagedUsers(List.of(userWithPassword("system-alice"))));
    adminClient()
        .realm(REALM)
        .users()
        .get(findUser("system-alice").getId())
        .resetPassword(password("Ch4nged-P4ssw0rd!"));

    withRealmManager(p -> p.provisionManagedUsers(List.of(userWithPassword("system-alice"))));

    assertThat(logIn("system-alice", PASSWORD)).isNotBlank();
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
                withRealmManager(
                    p -> p.provisionManagedUsers(List.of(userWithPassword("system-alice")))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining(policy.substring(0, policy.indexOf('(')));
    assertThat(usernames()).doesNotContain("system-alice");

    withRealmManager(p -> p.provisionManagedUsers(List.of(user("system-bob"))));
    assertThat(usernames()).contains("system-bob");
  }

  /** Keycloak's reset-password endpoint ignores the type and stores any value as the password. */
  @Test
  void rejectsCredentialsOtherThanPasswords() {
    UserRepresentation withOtp = user("system-alice");
    CredentialRepresentation otp = new CredentialRepresentation();
    otp.setType(CredentialRepresentation.TOTP);
    otp.setValue("123456");
    withOtp.setCredentials(List.of(otp));

    assertThatThrownBy(() -> withRealmManager(p -> p.provisionManagedUsers(List.of(withOtp))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("otp");
    assertThat(usernames()).doesNotContain("system-alice");
  }

  @Test
  void rejectsAMixedCaseUsername() {
    assertThatThrownBy(
            () -> withRealmManager(p -> p.provisionManagedUsers(List.of(user("system-Alice")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lower-case")
        .hasMessageContaining("system-Alice");

    assertThat(usernames()).doesNotContain("system-alice");
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

    withRealmManager(p -> p.provisionManagedUsers(List.of(user("system-alice"))));

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

  @Test
  void rejectsAUserDeclaringRolesOrGroups() {
    UserRepresentation withRoles = user("system-alice");
    withRoles.setRealmRoles(List.of("some-role"));

    assertThatThrownBy(() -> withRealmManager(p -> p.provisionManagedUsers(List.of(withRoles))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("realmRoles");

    UserRepresentation withGroups = user("system-bob");
    withGroups.setGroups(List.of("some-group"));

    assertThatThrownBy(() -> withRealmManager(p -> p.provisionManagedUsers(List.of(withGroups))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("groups");

    assertThat(usernames()).doesNotContain("system-alice", "system-bob");
  }

  /** Keycloak lower-cases a stored email, so a mixed-case one is never what the realm holds. */
  @Test
  void rejectsAnEmailThatIsNotLowerCase() {
    UserRepresentation mixedCase = user("system-alice");
    mixedCase.setEmail("Alice@Example.com");

    assertThatThrownBy(() -> withRealmManager(p -> p.provisionManagedUsers(List.of(mixedCase))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("email")
        .hasMessageContaining("Alice@Example.com");

    assertThat(usernames()).doesNotContain("system-alice");
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
