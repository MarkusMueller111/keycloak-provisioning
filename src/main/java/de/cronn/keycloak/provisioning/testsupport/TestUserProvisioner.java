package de.cronn.keycloak.provisioning.testsupport;

import de.cronn.keycloak.provisioning.RealmManager;
import de.cronn.keycloak.provisioning.Usernames;
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
 * Provisions a realm's users from a desired list. Kept apart from {@link RealmManager} because
 * users are rarely configuration: a realm that matters gets its accounts from people signing up or
 * from an identity provider, not from a list in a deployment's source code.
 *
 * <p>This is for realms whose whole user list is fixture data — a test container, a demo
 * environment, a seeded local setup. It ships in the main artifact rather than the test fixtures
 * because such realms are usually provisioned by production code running under a test or demo
 * profile.
 *
 * <p>{@link #createOrUpdateUsers(List)} takes the full desired state and deletes what is not in it,
 * the same semantics as the other reconciling methods. Users carry no naming prefix though, so
 * unlike the client reconciliation there is no fence limiting deletion to accounts this library
 * created: an incomplete list removes real accounts.
 */
public final class TestUserProvisioner {

  private static final Logger log = LoggerFactory.getLogger(TestUserProvisioner.class);

  private final RealmManager realmManager;

  public TestUserProvisioner(RealmManager realmManager) {
    this.realmManager = Objects.requireNonNull(realmManager);
  }

  /**
   * Brings the realm's users to exactly {@code users}, matched by username: missing ones are
   * created, existing ones updated, and <strong>every other user in the realm is deleted</strong>.
   *
   * <p>Credentials a user carries are applied on every run: Keycloak never returns their value, so
   * there is no way to tell whether they still match.
   *
   * <p>Usernames have to be lower-case — see {@link Usernames#requireLowerCase(String)}.
   */
  public void createOrUpdateUsers(List<UserRepresentation> users) {
    users.forEach(user -> Usernames.requireLowerCase(user.getUsername()));

    List<UserRepresentation> existingUsers =
        users.stream()
            .map(UserRepresentation::getUsername)
            .flatMap(realmManager::searchUsersByExactUsername)
            .toList();

    UserRepresentationDiffer differ = new UserRepresentationDiffer(existingUsers, users);
    differ.getElementsToAdd().forEach(this::addUser);
    differ.getElementsToUpdate().forEach(this::updateUser);

    users.forEach(this::applyCredentials);
    removeUsersNotIn(users);
  }

  /**
   * Service account users are safe from this: Keycloak leaves them out of the user listing it
   * walks, so the connection's own service account cannot be deleted out from under it.
   */
  private void removeUsersNotIn(List<UserRepresentation> users) {
    Set<String> keptUsernames =
        users.stream().map(UserRepresentation::getUsername).collect(Collectors.toSet());

    realmManager.getUsers(Integer.MAX_VALUE).stream()
        .filter(user -> !keptUsernames.contains(user.getUsername()))
        .forEach(
            user -> {
              log.info("Deleting unmanaged user '{}'", user.getUsername());
              realmManager.deleteUser(user.getId());
            });
  }

  private void addUser(UserRepresentation user) {
    log.info("Creating user '{}'", user.getUsername());
    realmManager.createUser(user);
  }

  private void updateUser(ToUpdate<UserRepresentation> update) {
    UserRepresentation newUser = update.newState();
    log.info(
        "User '{}' already exists, but update is required:\n{}",
        newUser.getUsername(),
        update.multiLineDiff());
    realmManager.getUserResource(newUser.getId()).update(newUser);
  }

  private void applyCredentials(UserRepresentation desiredUser) {
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
                      log.info(
                          "Setting {} credential for user '{}'",
                          credential.getType(),
                          desiredUser.getUsername());
                      userResource.resetPassword(credential);
                    }));
  }
}
