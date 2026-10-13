package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.differ.ToUpdate;
import de.cronn.keycloak.provisioning.differ.UserRepresentationDiffer;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions a realm's users from a desired list. Kept apart from {@link RealmManager} because it
 * only holds for a realm whose user list is fixed: nobody signs up, and no identity provider adds
 * accounts. A realm that gains users any other way will have them deleted.
 *
 * <p>{@link #provisionFixedUserList(List)} takes the full desired state and deletes what is not in
 * it, the same semantics as the other provisioning methods. Users carry no naming prefix though, so
 * unlike the client provisioning there is no fence limiting deletion to accounts this library
 * created: an incomplete list removes real accounts.
 */
public final class FixedUserListProvisioner {

  private static final Logger log = LoggerFactory.getLogger(FixedUserListProvisioner.class);

  private final RealmManager realmManager;

  public FixedUserListProvisioner(RealmManager realmManager) {
    this.realmManager = Objects.requireNonNull(realmManager);
  }

  /**
   * Brings the realm's users to exactly {@code users}, matched by username: missing ones are
   * created, existing ones updated, and <strong>every other user in the realm is deleted</strong>.
   *
   * <p>A declared password is set again on every run, which restores one changed out of band:
   * Keycloak never returns its value, so there is no way to tell whether it still matches. A
   * temporary one therefore asks for a new password again after every run. Only passwords are
   * supported, and not in a realm whose password policy forbids reusing one.
   *
   * <p>Usernames and emails have to be lower-case — see {@link UserFields}.
   */
  public ProvisioningResult provisionFixedUserList(List<UserRepresentation> users) {
    users.forEach(user -> UserFields.requireLowerCaseUsername(user.getUsername()));
    users.forEach(user -> UserFields.requireLowerCaseEmail(user.getEmail()));
    users.forEach(FixedUserListProvisioner::assertNoMembershipsAreDeclared);
    users.forEach(FixedUserListProvisioner::assertOnlyPasswordsAreDeclared);
    if (users.stream().anyMatch(FixedUserListProvisioner::declaresCredentials)) {
      assertPasswordsCanBeSetAgain();
    }

    List<UserRepresentation> existingUsers =
        users.stream()
            .map(UserRepresentation::getUsername)
            .flatMap(realmManager::searchUsersByExactUsername)
            .toList();

    Changes changes = new Changes(log, realmManager.getRealmName());
    UserRepresentationDiffer differ = new UserRepresentationDiffer(existingUsers, users);
    // A new user gets its password from the create request.
    differ.getElementsToAdd().forEach(user -> addUser(user, changes));
    differ.getElementsToUpdate().forEach(update -> updateUser(update, changes));

    Set<String> createdUsernames =
        differ.getElementsToAdd().stream()
            .map(UserRepresentation::getUsername)
            .collect(Collectors.toSet());
    users.stream()
        .filter(user -> !createdUsernames.contains(user.getUsername()))
        .forEach(user -> applyCredentials(user, changes));
    removeUsersNotIn(users, changes);
    return changes.result();
  }

  private static boolean declaresCredentials(UserRepresentation user) {
    return user.getCredentials() != null && !user.getCredentials().isEmpty();
  }

  /** Keycloak's reset-password endpoint ignores the type and stores any value as the password. */
  private static void assertOnlyPasswordsAreDeclared(UserRepresentation user) {
    if (user.getCredentials() == null) {
      return;
    }
    for (CredentialRepresentation credential : user.getCredentials()) {
      if (!CredentialRepresentation.PASSWORD.equals(credential.getType())) {
        throw new KeycloakException(
            "User '"
                + user.getUsername()
                + "' declares a credential of type '"
                + credential.getType()
                + "'. Only passwords can be provisioned.");
      }
    }
  }

  /** Both reject a reset to the current password, so the next run could never set it again. */
  private void assertPasswordsCanBeSetAgain() {
    String passwordPolicy = realmManager.getRealmResource().toRepresentation().getPasswordPolicy();
    if (passwordPolicy == null) {
      return;
    }
    for (String policy : List.of("passwordHistory", "passwordAge")) {
      if (passwordPolicy.contains(policy + "(")) {
        throw new KeycloakException(
            "Realm '"
                + realmManager.getRealmName()
                + "' has the password policy '"
                + policy
                + "', which forbids setting a password again. Remove it, or declare no passwords:"
                + " provisioning sets every declared password again on each run.");
      }
    }
  }

  /**
   * Keycloak ignores all three on creation and this class provisions none of them, so a declaration
   * would look applied while nothing was granted.
   */
  private static void assertNoMembershipsAreDeclared(UserRepresentation user) {
    assertNotDeclared(user.getUsername(), "realmRoles", user.getRealmRoles());
    assertNotDeclared(user.getUsername(), "clientRoles", user.getClientRoles());
    assertNotDeclared(user.getUsername(), "groups", user.getGroups());
  }

  private static void assertNotDeclared(String username, String what, Object value) {
    if (value != null) {
      throw new KeycloakException(
          "User '"
              + username
              + "' declares "
              + what
              + ", which this provisioner does not apply. Grant them through RealmManager instead.");
    }
  }

  /**
   * Service account users are safe from this: Keycloak leaves them out of the user listing it
   * walks, so the connection's own service account cannot be deleted out from under it.
   */
  private void removeUsersNotIn(List<UserRepresentation> users, Changes changes) {
    Set<String> keptUsernames =
        users.stream().map(UserRepresentation::getUsername).collect(Collectors.toSet());

    realmManager.getUsers(Integer.MAX_VALUE).stream()
        .filter(user -> !keptUsernames.contains(user.getUsername()))
        .forEach(
            user -> {
              changes.removed(Target.USER, user.getUsername());
              realmManager.deleteUser(user.getId());
            });
  }

  private void addUser(UserRepresentation user, Changes changes) {
    changes.added(Target.USER, user.getUsername());
    realmManager.createUser(user);
  }

  private void updateUser(ToUpdate<UserRepresentation> update, Changes changes) {
    UserRepresentation newUser = update.newState();
    changes.updated(Target.USER, newUser.getUsername(), update.multiLineDiff());
    realmManager.getUserResource(newUser.getId()).update(newUser);
  }

  private void applyCredentials(UserRepresentation desiredUser, Changes changes) {
    List<CredentialRepresentation> credentials = desiredUser.getCredentials();
    if (credentials == null || credentials.isEmpty()) {
      return;
    }
    realmManager
        .getUserResourceByUsername(desiredUser.getUsername())
        .ifPresent(
            userResource ->
                credentials.forEach(
                    credential -> {
                      changes.updated(Target.PASSWORD, desiredUser.getUsername(), null);
                      userResource.resetPassword(credential);
                    }));
  }
}
