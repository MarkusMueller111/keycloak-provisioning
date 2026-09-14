package de.cronn.keycloak.provisioning;

import java.util.Objects;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * How this library names the objects it manages. The prefix is a deletion fence: the reconciling
 * methods remove every object they recognize as managed that is no longer configured, so a realm
 * has to be provisioned with the same naming for its whole lifetime.
 *
 * <p>It means exactly that — "provisioned by this library" — and nothing else. It is not a tenant
 * or project marker: in a Keycloak that serves one application, every object belongs to that
 * application anyway, so {@link #standard()} is almost always the right choice.
 *
 * <p>Clients and groups get different prefixes because group names are shown to people in the admin
 * console, where {@code system-} reads badly.
 */
public final class ManagedNaming {

  public static final String STANDARD_CLIENT_PREFIX = "system-";
  public static final String STANDARD_GROUP_PREFIX = "[System] ";
  public static final String STANDARD_ROLE_PREFIX = "system-";

  private final String clientPrefix;
  private final String groupPrefix;
  private final String rolePrefix;

  private ManagedNaming(String clientPrefix, String groupPrefix, String rolePrefix) {
    this.clientPrefix = Validate.notBlank(clientPrefix, "client prefix must not be blank");
    this.groupPrefix = Validate.notBlank(groupPrefix, "group prefix must not be blank");
    this.rolePrefix = Validate.notBlank(rolePrefix, "role prefix must not be blank");
  }

  public static ManagedNaming standard() {
    return new ManagedNaming(STANDARD_CLIENT_PREFIX, STANDARD_GROUP_PREFIX, STANDARD_ROLE_PREFIX);
  }

  /**
   * Every prefix has to be given: group names are shown to people in the admin console, so reusing
   * a technical client prefix there produces names nobody wants to read.
   */
  public static ManagedNaming custom(String clientPrefix, String groupPrefix, String rolePrefix) {
    return new ManagedNaming(clientPrefix, groupPrefix, rolePrefix);
  }

  public ManagedName clientId(String clientId) {
    return name(ManagedEntity.CLIENT, clientId);
  }

  public ManagedName groupName(String groupName) {
    return name(ManagedEntity.GROUP, groupName);
  }

  public ManagedName roleName(String roleName) {
    return name(ManagedEntity.ROLE, roleName);
  }

  /**
   * Whether the client is one this library provisions, i.e. whether the reconciliation may delete
   * it. Takes the representation rather than a string because a client is fenced by its {@code
   * clientId}, while {@code getName()} is a display name that must not be used here.
   */
  public boolean isManaged(ClientRepresentation client) {
    return isManaged(ManagedEntity.CLIENT, client.getClientId());
  }

  public boolean isManaged(GroupRepresentation group) {
    return isManaged(ManagedEntity.GROUP, group.getName());
  }

  public boolean isManaged(RoleRepresentation role) {
    return isManaged(ManagedEntity.ROLE, role.getName());
  }

  /** For the cases where only the name is at hand; prefer the representation overloads. */
  public boolean isManaged(ManagedEntity entity, String name) {
    return Objects.requireNonNull(name).startsWith(prefix(entity));
  }

  public String prefix(ManagedEntity entity) {
    return switch (entity) {
      case CLIENT -> clientPrefix;
      case GROUP -> groupPrefix;
      case ROLE -> rolePrefix;
    };
  }

  private ManagedName name(ManagedEntity entity, String unprefixedName) {
    Validate.notBlank(unprefixedName, "name must not be blank");
    String prefix = prefix(entity);
    Validate.isTrue(
        !unprefixedName.startsWith(prefix),
        "name must be given without the '%s' prefix, but was '%s'",
        prefix,
        unprefixedName);
    return new ManagedName(unprefixedName, prefix + unprefixedName);
  }

  /** The kinds of object this library fences by name. */
  public enum ManagedEntity {
    CLIENT,
    GROUP,
    ROLE
  }
}
