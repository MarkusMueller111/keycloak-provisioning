package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.differ.AbstractDiffer;
import de.cronn.keycloak.provisioning.differ.UserRepresentationDiffer;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.Validate;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, changes and deletes users on demand, e.g. while the application runs. Memberships are
 * granted through {@link GroupAdministration}.
 *
 * <p>Every write is strict: creating a user that exists, or changing one that does not, fails
 * rather than falling back to the other. Usernames and emails have to be lower-case — see {@link
 * UserFields}.
 */
public final class UserAdministration {

  private static final Logger log = LoggerFactory.getLogger(UserAdministration.class);

  private static final String SERVICE_ACCOUNT_USERNAME_PREFIX = "service-account-";

  private final RealmManager realmManager;

  public UserAdministration(RealmManager realmManager) {
    this.realmManager = Objects.requireNonNull(realmManager);
  }

  /** Returns the new user's id. A declared password is set on creation. */
  public String createUser(UserRepresentation user) {
    requireValid(user);
    UserFields.requireOnlyPasswordsDeclared(user);
    if (realmManager.getUserByUsername(user.getUsername()).isPresent()) {
      throw new KeycloakException("User '" + user.getUsername() + "' already exists");
    }
    changes().added(Target.USER, user.getUsername());
    try (Response response = realmManager.getRealmResource().users().create(user)) {
      if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
        throw conflict(user, response);
      }
      RealmManager.assertResponseIs201Created(response);
      return CreatedResponseUtil.getCreatedId(response);
    }
  }

  /**
   * Brings the user to {@code user}, the full desired state of its fields: one left {@code null}
   * gets the value of a newly created user, so an undeclared {@code enabled} disables the account.
   * Matched by id, so a new username renames the user, if the realm allows editing usernames.
   * Passwords are set with {@link #resetPassword}.
   */
  public void updateUser(String userId, UserRepresentation user) {
    requireValid(user);
    Validate.isTrue(
        user.getCredentials() == null,
        "User '%s' declares credentials, which an update does not apply. Use resetPassword.",
        user.getUsername());
    UserRepresentation current = getChangeableUser(userId);

    AbstractDiffer.compare(current, user, UserRepresentationDiffer.updater())
        .ifPresent(
            update -> {
              changes().updated(Target.USER, user.getUsername(), update.multiLineDiff());
              try {
                realmManager.getUserResource(userId).update(update.newState());
              } catch (ClientErrorException e) {
                if (e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                  throw conflict(user, e.getResponse());
                }
                throw e;
              }
            });
  }

  /** Any user, managed or not. */
  public Optional<UserRepresentation> findUserByUsername(String username) {
    Validate.notBlank(username, "username must not be blank");
    return realmManager.getUserByUsername(username);
  }

  public void deleteUser(String userId) {
    UserRepresentation user = getChangeableUser(userId);
    changes().removed(Target.USER, user.getUsername());
    realmManager.deleteUser(userId);
  }

  /** A temporary password has to be changed on the next login. */
  public void resetPassword(String userId, String password, boolean temporary) {
    Validate.notEmpty(password, "password must not be empty");
    UserRepresentation user = getChangeableUser(userId);
    CredentialRepresentation credential = new CredentialRepresentation();
    credential.setType(CredentialRepresentation.PASSWORD);
    credential.setValue(password);
    credential.setTemporary(temporary);
    changes().updated(Target.PASSWORD, user.getUsername(), null);
    realmManager.getUserResource(userId).resetPassword(credential);
  }

  private static void requireValid(UserRepresentation user) {
    UserFields.requireLowerCaseUsername(user.getUsername());
    UserFields.requireLowerCaseEmail(user.getEmail());
    UserFields.requireNoMembershipsDeclared(user);
  }

  private UserRepresentation getChangeableUser(String userId) {
    Validate.notBlank(userId, "user id must not be blank");
    UserResource userResource = realmManager.getUserResource(userId);
    UserRepresentation user;
    try {
      user = userResource.toRepresentation();
    } catch (NotFoundException e) {
      throw new KeycloakException("No user with id '" + userId + "'", e);
    }
    if (isServiceAccount(user)) {
      throw new KeycloakException(
          "User '"
              + user.getUsername()
              + "' is a client's service account, which belongs to the client and cannot be"
              + " changed on its own");
    }
    return user;
  }

  /**
   * Keycloak leaves {@code serviceAccountClientId} unset on every user it returns, so the link is
   * only visible from the client's side. Its username is derived from the client id, lower-cased.
   */
  private boolean isServiceAccount(UserRepresentation user) {
    if (!user.getUsername().startsWith(SERVICE_ACCOUNT_USERNAME_PREFIX)) {
      return false;
    }
    return realmManager.getRealmResource().clients().findAll().stream()
        .filter(client -> Boolean.TRUE.equals(client.isServiceAccountsEnabled()))
        .anyMatch(
            client ->
                user.getId()
                    .equals(
                        realmManager
                            .getRealmResource()
                            .clients()
                            .get(client.getId())
                            .getServiceAccountUser()
                            .getId()));
  }

  /** Keycloak tells a taken username from a taken email only in the message. */
  private static KeycloakException conflict(UserRepresentation user, Response response) {
    if (response.readEntity(String.class).contains("email")) {
      return new KeycloakException(
          "User '"
              + user.getUsername()
              + "' cannot have the email '"
              + user.getEmail()
              + "': another user has it");
    }
    return new KeycloakException("User '" + user.getUsername() + "' already exists");
  }

  private Changes changes() {
    return new Changes(log, realmManager.getRealmName());
  }
}
