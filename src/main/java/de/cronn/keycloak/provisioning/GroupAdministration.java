package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.ManagedNaming.ManagedEntity;
import de.cronn.keycloak.provisioning.differ.AbstractDiffer;
import de.cronn.keycloak.provisioning.differ.GroupRepresentationDiffer;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.commons.lang3.Validate;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, changes and deletes groups on demand, e.g. while the application runs, as opposed to
 * {@link RealmManager#provisionManagedGroups(List)}, which converges the realm on a full desired
 * state.
 *
 * <p>Only groups outside the managed namespace can be written here, and they are kept apart from
 * the managed ones: none is created below a managed group, where it would also disappear with it.
 * Memberships are references, not part of the tree, so users may join managed groups as well.
 *
 * <p>Every write is strict: creating something that exists, or changing something that does not,
 * fails rather than falling back to the other.
 */
public final class GroupAdministration {

  private static final Logger log = LoggerFactory.getLogger(GroupAdministration.class);

  private final RealmManager realmManager;

  public GroupAdministration(RealmManager realmManager) {
    this.realmManager = Objects.requireNonNull(realmManager);
  }

  /** Creates a top-level group with its sub-groups and role mappings, and returns its id. */
  public String createGroup(GroupConfig group) {
    return create(null, realmManager.getGroupsResource()::add, group);
  }

  /** Creates a group with its sub-groups and role mappings below an unmanaged one. */
  public String createSubGroup(String parentGroupId, GroupConfig group) {
    GroupRepresentation parent = getUnmanagedGroup(parentGroupId);
    assertNoManagedAncestor(parent);
    return create(parent.getPath(), realmManager.getGroupResource(parent.getId())::subGroup, group);
  }

  private String create(
      String parentPath, Function<GroupRepresentation, Response> create, GroupConfig group) {
    Changes changes = changes();
    GroupTrees trees = new GroupTrees(realmManager, false, List.of(group), parentPath, changes);
    return trees.provision(List.of(), List.of(group), parentPath, create).get(group.name());
  }

  /**
   * Brings the group's name, description, attributes and role mappings to {@code group}. Matched by
   * id, so a new name renames the group and keeps its members. Its sub-groups are left alone, so
   * {@code group} must declare none.
   */
  public void updateGroup(String groupId, GroupConfig group) {
    Validate.isTrue(
        group.subGroups().isEmpty(),
        "Group '%s' declares sub-groups, which an update does not apply. Use replaceGroupTree, or"
            + " createSubGroup and deleteGroup.",
        group.name());
    update(groupId, group, false);
  }

  /**
   * Like {@link #updateGroup}, and also brings the sub-groups at every level to the declared ones:
   * an undeclared sub-group is deleted together with its memberships. Below the group itself,
   * groups are matched by name, so renaming a sub-group recreates it without its members.
   */
  public void replaceGroupTree(String groupId, GroupConfig group) {
    update(groupId, group, true);
  }

  private void update(String groupId, GroupConfig group, boolean withSubGroups) {
    GroupRepresentation current = getUnmanagedGroup(groupId);
    String parentPath = parentPath(current);
    Changes changes = changes();
    GroupTrees trees = new GroupTrees(realmManager, false, List.of(group), parentPath, changes);

    GroupResource groupResource = realmManager.getGroupResource(groupId);
    AbstractDiffer.compare(
            current, GroupTrees.toRepresentation(group), GroupRepresentationDiffer.updater())
        .ifPresent(
            update -> {
              changes.updated(Target.GROUP, group.name(), parentPath, update.multiLineDiff());
              try {
                groupResource.update(update.newState());
              } catch (ClientErrorException e) {
                if (e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                  throw new KeycloakException(
                      "Group '" + GroupTrees.path(parentPath, group.name()) + "' already exists",
                      e);
                }
                throw e;
              }
            });
    String path = GroupTrees.path(parentPath, group.name());
    trees.provisionRoles(groupResource, false, group, path);
    if (withSubGroups) {
      trees.provision(
          trees.onThisSide(groupResource.getSubGroups(0, Integer.MAX_VALUE, false)),
          group.subGroups(),
          path,
          groupResource::subGroup);
    }
  }

  /** Deletes the group together with everything below it, including its members' memberships. */
  public void deleteGroup(String groupId) {
    GroupRepresentation group = getUnmanagedGroup(groupId);
    changes().removed(Target.GROUP, group.getName(), parentPath(group));
    realmManager.getGroupResource(groupId).remove();
  }

  /**
   * Groups at any level whose attribute {@code key} holds {@code value}: a stable handle for a
   * group whose name may change.
   */
  public List<GroupRepresentation> findGroupsByAttribute(String key, String value) {
    Validate.notBlank(key, "attribute key must not be blank");
    Objects.requireNonNull(value);
    List<GroupRepresentation> found =
        realmManager
            .getGroupsResource()
            .query(key + ":" + value, true, 0, Integer.MAX_VALUE, false);
    return flatten(found)
        .filter(
            group ->
                group.getAttributes() != null
                    && group.getAttributes().getOrDefault(key, List.of()).contains(value))
        .toList();
  }

  /** Any group, managed or not. */
  public Optional<GroupRepresentation> findGroupById(String groupId) {
    Validate.notBlank(groupId, "group id must not be blank");
    try {
      return Optional.of(realmManager.getGroupResource(groupId).toRepresentation());
    } catch (NotFoundException e) {
      return Optional.empty();
    }
  }

  /**
   * Any group, managed or not. The path is the group names joined by {@code /} with a leading one,
   * e.g. {@code "/[System] Staff/[System] Editors"}.
   */
  public Optional<GroupRepresentation> findGroupByPath(String path) {
    try {
      return Optional.of(realmManager.getRealmResource().getGroupByPath(path));
    } catch (NotFoundException e) {
      return Optional.empty();
    }
  }

  /** Any group, managed or not: a membership is a reference, not part of the tree. */
  public void addMember(String groupId, String userId) {
    GroupRepresentation group = getGroup(groupId);
    UserResource user = realmManager.getUserResource(userId);
    String username = getUser(user, userId).getUsername();
    if (isMember(user, groupId)) {
      throw new KeycloakException(
          "User '" + username + "' is already a member of group '" + group.getPath() + "'");
    }
    changes().added(Target.GROUP_MEMBER, username, group.getPath());
    user.joinGroup(groupId);
  }

  public void removeMember(String groupId, String userId) {
    GroupRepresentation group = getGroup(groupId);
    UserResource user = realmManager.getUserResource(userId);
    String username = getUser(user, userId).getUsername();
    if (!isMember(user, groupId)) {
      throw new KeycloakException(
          "User '" + username + "' is not a member of group '" + group.getPath() + "'");
    }
    changes().removed(Target.GROUP_MEMBER, username, group.getPath());
    user.leaveGroup(groupId);
  }

  private static boolean isMember(UserResource user, String groupId) {
    return user.groups(0, Integer.MAX_VALUE, true).stream()
        .anyMatch(group -> groupId.equals(group.getId()));
  }

  private static UserRepresentation getUser(UserResource user, String userId) {
    try {
      return user.toRepresentation();
    } catch (NotFoundException e) {
      throw new KeycloakException("No user with id '" + userId + "'", e);
    }
  }

  private static Stream<GroupRepresentation> flatten(List<GroupRepresentation> groups) {
    if (groups == null) {
      return Stream.empty();
    }
    return groups.stream()
        .flatMap(group -> Stream.concat(Stream.of(group), flatten(group.getSubGroups())));
  }

  private GroupRepresentation getGroup(String groupId) {
    Validate.notBlank(groupId, "group id must not be blank");
    try {
      return realmManager.getGroupResource(groupId).toRepresentation();
    } catch (NotFoundException e) {
      throw new KeycloakException("No group with id '" + groupId + "'", e);
    }
  }

  private GroupRepresentation getUnmanagedGroup(String groupId) {
    GroupRepresentation group = getGroup(groupId);
    if (isManaged(group)) {
      throw new KeycloakException(
          "Group '"
              + group.getPath()
              + "' is managed by provisioning and cannot be changed on demand");
    }
    return group;
  }

  private void assertNoManagedAncestor(GroupRepresentation parent) {
    GroupRepresentation current = parent;
    while (current != null) {
      if (isManaged(current)) {
        throw new KeycloakException(
            "Cannot create a group below '"
                + parent.getPath()
                + "': it lies within the managed group '"
                + current.getPath()
                + "', and managed and unmanaged groups are not nested in each other");
      }
      current = current.getParentId() == null ? null : getGroup(current.getParentId());
    }
  }

  private boolean isManaged(GroupRepresentation group) {
    return realmManager.naming().isManaged(ManagedEntity.GROUP, group.getName());
  }

  private static String parentPath(GroupRepresentation group) {
    return group.getParentId() == null
        ? null
        : group.getPath().substring(0, group.getPath().length() - group.getName().length() - 1);
  }

  private Changes changes() {
    return new Changes(log, realmManager.getRealmName());
  }
}
