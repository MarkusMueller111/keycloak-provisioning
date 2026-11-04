package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.ManagedNaming.ManagedEntity;
import de.cronn.keycloak.provisioning.differ.AbstractDiffer;
import de.cronn.keycloak.provisioning.differ.GroupRepresentationDiffer;
import de.cronn.keycloak.provisioning.differ.ToUpdate;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.apache.commons.lang3.Validate;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * Writes group trees and their role mappings. Shared by the provisioning of managed groups and the
 * on-demand administration of unmanaged ones, which differ only in which side of the fence a name
 * has to be on.
 */
final class GroupTrees {

  private final RealmManager realmManager;
  private final boolean managed;
  private final Changes changes;
  private final Map<String, RoleRepresentation> declaredRealmRolesByName = new LinkedHashMap<>();
  private final Map<String, DeclaredClient> declaredClientsByClientId = new LinkedHashMap<>();

  /** Validates every name and resolves every declared role, before the caller's first write. */
  GroupTrees(
      RealmManager realmManager,
      boolean managed,
      List<GroupConfig> groups,
      String parentPath,
      Changes changes) {
    this.realmManager = realmManager;
    this.managed = managed;
    this.changes = changes;
    assertNames(groups, parentPath);
    resolveDeclaredRoles(groups, parentPath);
  }

  /**
   * Diffs one level of the tree by name and recurses into the declared groups. Of the existing
   * sub-groups only those on this side of the fence are diffed. Returns the ids of this level's
   * declared groups by name.
   */
  Map<String, String> provision(
      List<GroupRepresentation> existingGroups,
      List<GroupConfig> groups,
      String parentPath,
      Function<GroupRepresentation, Response> create) {
    GroupRepresentationDiffer differ =
        new GroupRepresentationDiffer(
            existingGroups, groups.stream().map(GroupTrees::toRepresentation).toList());

    Map<String, String> idsByName = new LinkedHashMap<>();
    existingGroups.forEach(group -> idsByName.put(group.getName(), group.getId()));
    for (GroupRepresentation group : differ.getElementsToAdd()) {
      changes.added(Target.GROUP, group.getName(), parentPath);
      try (Response response = create.apply(group)) {
        assertCreated(response, path(parentPath, group.getName()));
        idsByName.put(group.getName(), CreatedResponseUtil.getCreatedId(response));
      }
    }
    for (GroupRepresentation group : differ.getElementsToDelete()) {
      changes.removed(Target.GROUP, group.getName(), parentPath);
      realmManager.getGroupResource(group.getId()).remove();
    }
    for (ToUpdate<GroupRepresentation> update : differ.getElementsToUpdate()) {
      GroupRepresentation group = update.newState();
      changes.updated(Target.GROUP, group.getName(), parentPath, update.multiLineDiff());
      realmManager.getGroupResource(group.getId()).update(group);
    }

    Set<String> created =
        differ.getElementsToAdd().stream()
            .map(GroupRepresentation::getName)
            .collect(Collectors.toSet());
    for (GroupConfig group : groups) {
      boolean isNew = created.contains(group.name());
      GroupResource groupResource = realmManager.getGroupResource(idsByName.get(group.name()));
      String path = path(parentPath, group.name());
      provisionRoles(groupResource, isNew, group, path);
      provision(
          isNew ? List.of() : onThisSide(groupResource.getSubGroups(0, Integer.MAX_VALUE, false)),
          group.subGroups(),
          path,
          groupResource::subGroup);
    }
    return idsByName;
  }

  List<GroupRepresentation> onThisSide(List<GroupRepresentation> groups) {
    return groups.stream().filter(group -> isManaged(group.getName()) == managed).toList();
  }

  void provisionRoles(GroupResource groupResource, boolean isNew, GroupConfig group, String path) {
    MappingsRepresentation mapped =
        isNew ? new MappingsRepresentation() : groupResource.roles().getAll();
    provisionRoleMappings(
        mapped.getRealmMappings(),
        group.realmRoles(),
        declaredRealmRolesByName::get,
        groupResource.roles().realmLevel(),
        Target.GROUP_REALM_ROLE,
        UnaryOperator.identity(),
        path);

    Map<String, ClientMappingsRepresentation> mappedByClientId =
        mapped.getClientMappings() == null ? Map.of() : mapped.getClientMappings();
    Set<String> clientIds = new LinkedHashSet<>(mappedByClientId.keySet());
    clientIds.addAll(group.clientRoles().keySet());
    for (String clientId : clientIds) {
      ClientMappingsRepresentation mappedForClient = mappedByClientId.get(clientId);
      String clientUuid =
          mappedForClient != null
              ? mappedForClient.getId()
              : declaredClientsByClientId.get(clientId).uuid();
      provisionRoleMappings(
          mappedForClient == null ? null : mappedForClient.getMappings(),
          group.clientRoles().getOrDefault(clientId, List.of()),
          roleName -> declaredClientsByClientId.get(clientId).rolesByName().get(roleName),
          groupResource.roles().clientLevel(clientUuid),
          Target.GROUP_CLIENT_ROLE,
          roleName -> clientId + ":" + roleName,
          path);
    }
  }

  private void provisionRoleMappings(
      List<RoleRepresentation> mapped,
      List<String> declared,
      Function<String, RoleRepresentation> resolveDeclared,
      RoleScopeResource scope,
      Target target,
      UnaryOperator<String> changeName,
      String path) {
    List<RoleRepresentation> mappedOrEmpty = mapped == null ? List.of() : mapped;
    AbstractDiffer<String> differ =
        new AbstractDiffer<>(
            mappedOrEmpty.stream().map(RoleRepresentation::getName).toList(), declared);

    List<RoleRepresentation> toRemove =
        mappedOrEmpty.stream()
            .filter(role -> differ.getElementsToDelete().contains(role.getName()))
            .toList();
    if (!toRemove.isEmpty()) {
      toRemove.forEach(role -> changes.removed(target, changeName.apply(role.getName()), path));
      scope.remove(toRemove);
    }
    List<RoleRepresentation> toAdd =
        differ.getElementsToAdd().stream().map(resolveDeclared).toList();
    if (!toAdd.isEmpty()) {
      toAdd.forEach(role -> changes.added(target, changeName.apply(role.getName()), path));
      scope.add(toAdd);
    }
  }

  static GroupRepresentation toRepresentation(GroupConfig config) {
    GroupRepresentation group = new GroupRepresentation();
    group.setName(config.name());
    group.setDescription(config.description());
    group.setAttributes(config.attributes());
    return group;
  }

  static String path(String parentPath, String name) {
    return (parentPath == null ? "" : parentPath) + "/" + name;
  }

  private static void assertCreated(Response response, String path) {
    if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
      throw new KeycloakException("Group '" + path + "' already exists");
    }
    RealmManager.assertResponseIs201Created(response);
  }

  private boolean isManaged(String name) {
    return realmManager.naming().isManaged(ManagedEntity.GROUP, name);
  }

  /**
   * A managed group without the prefix would be created but never recognized as existing again, so
   * the next run would fail with a conflict; an unmanaged one with it would be deleted by the next
   * provisioning run.
   */
  private void assertNames(List<GroupConfig> groups, String parentPath) {
    Set<String> seen = new LinkedHashSet<>();
    for (GroupConfig group : groups) {
      String path = path(parentPath, group.name());
      if (isManaged(group.name()) != managed) {
        throw new KeycloakException(
            "Group '"
                + path
                + "' cannot be "
                + (managed ? "provisioned" : "created on demand")
                + ": its name must "
                + (managed ? "" : "not ")
                + "carry the configured prefix '"
                + realmManager.naming().prefix(ManagedEntity.GROUP)
                + "'.");
      }
      Validate.isTrue(seen.add(group.name()), "Group '%s' is declared more than once", path);
      assertNames(group.subGroups(), path);
    }
  }

  private void resolveDeclaredRoles(List<GroupConfig> groups, String parentPath) {
    for (GroupConfig group : groups) {
      String path = path(parentPath, group.name());
      for (String roleName : group.realmRoles()) {
        declaredRealmRolesByName.computeIfAbsent(
            roleName,
            name ->
                findRole(realmManager.getRealmResource().roles(), name)
                    .orElseThrow(
                        () ->
                            new KeycloakException(
                                "Group '"
                                    + path
                                    + "' declares realm role '"
                                    + name
                                    + "', which does not exist")));
      }
      group
          .clientRoles()
          .forEach((clientId, roleNames) -> resolveClientRoles(clientId, roleNames, path));
      resolveDeclaredRoles(group.subGroups(), path);
    }
  }

  private void resolveClientRoles(String clientId, List<String> roleNames, String path) {
    DeclaredClient client =
        declaredClientsByClientId.computeIfAbsent(
            clientId, id -> new DeclaredClient(findClientUuid(id, path), new LinkedHashMap<>()));
    for (String roleName : roleNames) {
      client
          .rolesByName()
          .computeIfAbsent(
              roleName,
              name ->
                  findRole(
                          realmManager.getRealmResource().clients().get(client.uuid()).roles(),
                          name)
                      .orElseThrow(
                          () ->
                              new KeycloakException(
                                  "Group '"
                                      + path
                                      + "' declares role '"
                                      + name
                                      + "' of client '"
                                      + clientId
                                      + "', which does not exist")));
    }
  }

  private String findClientUuid(String clientId, String path) {
    return realmManager.getRealmResource().clients().findByClientId(clientId).stream()
        .findFirst()
        .map(ClientRepresentation::getId)
        .orElseThrow(
            () ->
                new KeycloakException(
                    "Group '"
                        + path
                        + "' declares roles of client '"
                        + clientId
                        + "', which does not exist"));
  }

  private static Optional<RoleRepresentation> findRole(RolesResource roles, String name) {
    try {
      return Optional.of(roles.get(name).toRepresentation());
    } catch (NotFoundException e) {
      return Optional.empty();
    }
  }

  /** A client whose roles the groups declare, with those roles resolved by name. */
  private record DeclaredClient(String uuid, Map<String, RoleRepresentation> rolesByName) {}
}
