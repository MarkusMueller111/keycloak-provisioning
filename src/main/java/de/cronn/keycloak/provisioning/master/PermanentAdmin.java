package de.cronn.keycloak.provisioning.master;

import de.cronn.keycloak.provisioning.RealmManager;
import de.cronn.keycloak.provisioning.UserFields;
import de.cronn.keycloak.provisioning.UserProfileConfig;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * The realm's permanent administrator, taking over administrative access from Keycloak's temporary
 * bootstrap admin.
 *
 * <p>Created once: {@link MasterRealmProvisioner#provision()} only creates it if no user with that
 * username exists yet, so later configuration changes never touch an existing admin.
 */
public sealed interface PermanentAdmin {

  UserRepresentation buildUser();

  /**
   * Keycloak's user creation silently ignores {@link UserRepresentation#getRealmRoles()}, so the
   * roles set there are granted in a separate call after the user exists. Defaults to {@link
   * RealmManager#ADMIN_REALM_ROLE} when the representation leaves them unset.
   */
  static List<String> realmRolesOf(UserRepresentation user) {
    List<String> realmRoles = user.getRealmRoles();
    return realmRoles == null ? List.of(RealmManager.ADMIN_REALM_ROLE) : List.copyOf(realmRoles);
  }

  /** Called once, after the user was created and its realm roles were granted. */
  void afterCreate(UserResource userResource);

  /**
   * No standing human admin. Together with the default {@code deleteTemporaryAdmins(true)} the
   * realm is left without administrative access by design; create one on demand via {@code kc.sh
   * bootstrap-admin user}.
   */
  static PermanentAdmin none() {
    return None.INSTANCE;
  }

  /**
   * A {@code null} email works only with a user profile that makes email optional: the default
   * {@link UserProfileConfig} requires one for admins, and Keycloak then rejects the user creation
   * with {@code error-user-attribute-required}.
   *
   * <p>{@code initialPassword} has to satisfy the realm's {@link
   * MasterRealmConfig#passwordPolicy()}, and {@code username} and {@code email} have to be
   * lower-case — see {@link UserFields}.
   */
  record Standard(String username, String initialPassword, String email) implements PermanentAdmin {

    public Standard {
      UserFields.requireLowerCaseUsername(username);
      UserFields.requireLowerCaseEmail(email);
      Validate.notBlank(initialPassword, "initialPassword must not be blank");
    }

    @Override
    public UserRepresentation buildUser() {
      UserRepresentation user = new UserRepresentation();
      user.setUsername(username);
      user.setEmail(email);
      user.setEmailVerified(email != null);
      user.setEnabled(true);
      return user;
    }

    @Override
    public void afterCreate(UserResource userResource) {
      CredentialRepresentation credential = new CredentialRepresentation();
      credential.setType(CredentialRepresentation.PASSWORD);
      credential.setValue(initialPassword);
      credential.setTemporary(true);
      userResource.resetPassword(credential);
    }
  }

  /**
   * Escape hatch for admins not modeled by {@link Standard}. The username and the realm roles are
   * taken from the built {@link UserRepresentation}; {@code afterCreate} runs once against the
   * created user, typically to set a credential type {@link Standard} does not cover.
   */
  record Custom(UserRepresentation user, Consumer<UserResource> afterCreate)
      implements PermanentAdmin {

    public Custom {
      Objects.requireNonNull(user, "user must not be null");
      afterCreate = afterCreate == null ? userResource -> {} : afterCreate;
    }

    @Override
    public UserRepresentation buildUser() {
      UserRepresentation userRepresentation =
          Objects.requireNonNull(user, "user must not supply null");
      UserFields.requireLowerCaseUsername(userRepresentation.getUsername());
      UserFields.requireLowerCaseEmail(userRepresentation.getEmail());
      return userRepresentation;
    }

    @Override
    public void afterCreate(UserResource userResource) {
      afterCreate.accept(userResource);
    }
  }

  /**
   * @see PermanentAdmin#none()
   */
  record None() implements PermanentAdmin {

    private static final None INSTANCE = new None();

    @Override
    public UserRepresentation buildUser() {
      throw new UnsupportedOperationException("PermanentAdmin.none() creates no user");
    }

    @Override
    public void afterCreate(UserResource userResource) {
      throw new UnsupportedOperationException("PermanentAdmin.none() creates no user");
    }
  }
}
