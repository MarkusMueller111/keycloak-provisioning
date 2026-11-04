package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.Change.Type;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

class GroupProvisioningTest extends SharedKeycloakTest {

  private static final ManagedNaming NAMING = ManagedNaming.standard();

  @BeforeEach
  void createRoles() {
    List.of("reader", "writer", "auditor")
        .forEach(
            name -> {
              RoleRepresentation role = new RoleRepresentation();
              role.setName(name);
              realm().roles().create(role);
            });
    createClientWithRoles("system-api", "use", "admin");
    createClientWithRoles("system-other", "use");
  }

  @Test
  void createsTheGroupTree() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));

    withRealmManager(this::validateGroups);
  }

  @Test
  void reportsEveryWriteWithTheParentPath() {
    withRealmManager(
        rm ->
            assertThat(rm.provisionManagedGroups(List.of(staffTree())).changes())
                .extracting(Change::target, Change::name, Change::parent, Change::type)
                .containsExactly(
                    tuple(Target.GROUP, name("Staff"), null, Type.ADDED),
                    tuple(Target.GROUP_REALM_ROLE, "reader", path("Staff"), Type.ADDED),
                    tuple(Target.GROUP_CLIENT_ROLE, "system-api:use", path("Staff"), Type.ADDED),
                    tuple(Target.GROUP, name("Editors"), path("Staff"), Type.ADDED),
                    tuple(Target.GROUP, name("Auditors"), path("Staff"), Type.ADDED),
                    tuple(Target.GROUP_REALM_ROLE, "writer", path("Staff", "Editors"), Type.ADDED),
                    tuple(Target.GROUP, name("Senior"), path("Staff", "Editors"), Type.ADDED),
                    tuple(
                        Target.GROUP_CLIENT_ROLE,
                        "system-api:admin",
                        path("Staff", "Editors", "Senior"),
                        Type.ADDED),
                    tuple(
                        Target.GROUP_REALM_ROLE,
                        "auditor",
                        path("Staff", "Auditors"),
                        Type.ADDED)));
  }

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));

    withRealmManager(
        rm -> assertChangesNothing(() -> rm.provisionManagedGroups(List.of(staffTree()))));
  }

  @Test
  void deletesGroupsNoLongerDeclaredAtEveryLevel() {
    withRealmManager(
        rm ->
            rm.provisionManagedGroups(
                List.of(staffTree(), GroupConfig.builder(name("Old")).build())));

    GroupConfig pruned =
        GroupConfig.builder(name("Staff"))
            .subGroups(GroupConfig.builder(name("Editors")).realmRoles("writer").build())
            .build();
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(pruned)));

    withRealmManager(this::validateGroups);
  }

  @Test
  void deletesAPrefixedSubGroupCreatedByHand() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));
    createSubGroup(groupByPath(path("Staff")).getId(), name("Interns"));

    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));

    withRealmManager(this::validateGroups);
  }

  @Test
  void keepsAnUnprefixedSubGroupCreatedByHandUnderAManagedGroup() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));
    createSubGroup(groupByPath(path("Staff")).getId(), "Interns");

    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));

    withRealmManager(this::validateGroups);
  }

  /** Keycloak creates no groups of its own, so the only groups at risk are ones made by hand. */
  @Test
  void neverTouchesAGroupWithoutThePrefix() {
    String unmanagedId = createTopLevelGroup("Customers");
    createSubGroup(unmanagedId, name("Premium"));

    withRealmManager(rm -> rm.provisionManagedGroups(List.of()));

    withRealmManager(this::validateGroups);
  }

  @Test
  void removesDescriptionAndAttributesNoLongerDeclared() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));

    GroupConfig staff =
        GroupConfig.builder(name("Staff"))
            .realmRoles("reader")
            .clientRoles("system-api", "use")
            .subGroups(editors(), auditors())
            .build();
    withRealmManager(
        rm ->
            assertThat(rm.provisionManagedGroups(List.of(staff)).changes())
                .extracting(Change::target, Change::name, Change::type)
                .containsExactly(tuple(Target.GROUP, name("Staff"), Type.UPDATED)));

    withRealmManager(this::validateGroups);
  }

  @Test
  void replacesTheRealmRolesOfAGroup() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));
    roleMappings(path("Staff"))
        .realmLevel()
        .add(List.of(realm().roles().get("auditor").toRepresentation()));

    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffWith("writer", "use"))));

    withRealmManager(this::validateGroups);
  }

  @Test
  void replacesTheClientRolesOfAGroup() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffTree())));
    mapClientRole(path("Staff"), "system-other", "use");

    withRealmManager(rm -> rm.provisionManagedGroups(List.of(staffWith("reader", "admin"))));

    withRealmManager(this::validateGroups);
  }

  @Test
  void rejectsASubGroupWithoutThePrefix() {
    GroupConfig unprefixed =
        GroupConfig.builder(name("Staff"))
            .subGroups(GroupConfig.builder("Editors").build())
            .build();

    assertThatThrownBy(() -> withRealmManager(rm -> rm.provisionManagedGroups(List.of(unprefixed))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining(path("Staff") + "/Editors")
        .hasMessageContaining("[System] ");

    assertThat(topLevelGroupNames()).isEmpty();
  }

  @Test
  void rejectsAnUnknownRealmRoleBeforeTheFirstWrite() {
    GroupConfig unknownRole =
        GroupConfig.builder(name("Staff"))
            .subGroups(GroupConfig.builder(name("Editors")).realmRoles("no-such-role").build())
            .build();

    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.provisionManagedGroups(List.of(unknownRole))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("no-such-role")
        .hasMessageContaining(path("Staff", "Editors"));

    assertThat(topLevelGroupNames()).isEmpty();
  }

  @Test
  void rejectsAnUnknownClientOrClientRoleBeforeTheFirstWrite() {
    GroupConfig unknownClient =
        GroupConfig.builder(name("Staff")).clientRoles("system-nobody", "use").build();
    GroupConfig unknownRole =
        GroupConfig.builder(name("Staff")).clientRoles("system-api", "no-such-role").build();

    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.provisionManagedGroups(List.of(unknownClient))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("system-nobody");
    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.provisionManagedGroups(List.of(unknownRole))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("no-such-role")
        .hasMessageContaining("system-api");

    assertThat(topLevelGroupNames()).isEmpty();
  }

  @Test
  void rejectsSiblingsWithTheSameName() {
    GroupConfig duplicated =
        GroupConfig.builder(name("Staff"))
            .subGroups(
                GroupConfig.builder(name("Editors")).build(),
                GroupConfig.builder(name("Editors")).build())
            .build();

    assertThatThrownBy(() -> withRealmManager(rm -> rm.provisionManagedGroups(List.of(duplicated))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(path("Staff", "Editors"));

    assertThat(topLevelGroupNames()).isEmpty();
  }

  @Test
  void rejectsASlashInAGroupName() {
    assertThatThrownBy(() -> GroupConfig.builder("Sales/Marketing").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Sales/Marketing");
  }

  private static GroupConfig staffTree() {
    return GroupConfig.builder(name("Staff"))
        .description("Everyone employed")
        .attribute("cost-center", "b", "a")
        .realmRoles("reader")
        .clientRoles("system-api", "use")
        .subGroups(editors(), auditors())
        .build();
  }

  private static GroupConfig staffWith(String realmRole, String apiRole) {
    return GroupConfig.builder(name("Staff"))
        .description("Everyone employed")
        .attribute("cost-center", "b", "a")
        .realmRoles(realmRole)
        .clientRoles("system-api", apiRole)
        .subGroups(editors(), auditors())
        .build();
  }

  private static GroupConfig editors() {
    return GroupConfig.builder(name("Editors"))
        .realmRoles("writer")
        .subGroups(GroupConfig.builder(name("Senior")).clientRoles("system-api", "admin").build())
        .build();
  }

  private static GroupConfig auditors() {
    return GroupConfig.builder(name("Auditors")).realmRoles("auditor").build();
  }

  private static String name(String unprefixed) {
    return NAMING.groupName(unprefixed).value();
  }

  private static String path(String... unprefixedNames) {
    return Arrays.stream(unprefixedNames)
        .map(GroupProvisioningTest::name)
        .collect(Collectors.joining("/", "/", ""));
  }

  private RealmResource realm() {
    return adminClient().realm(REALM);
  }

  private GroupRepresentation groupByPath(String path) {
    return realm().getGroupByPath(path);
  }

  private RoleMappingResource roleMappings(String path) {
    return realm().groups().group(groupByPath(path).getId()).roles();
  }

  private void mapClientRole(String path, String clientId, String roleName) {
    String clientUuid = realm().clients().findByClientId(clientId).getFirst().getId();
    roleMappings(path)
        .clientLevel(clientUuid)
        .add(List.of(realm().clients().get(clientUuid).roles().get(roleName).toRepresentation()));
  }

  private List<String> topLevelGroupNames() {
    return realm().groups().groups().stream().map(GroupRepresentation::getName).toList();
  }

  private String createTopLevelGroup(String name) {
    GroupRepresentation group = new GroupRepresentation();
    group.setName(name);
    try (Response response = realm().groups().add(group)) {
      return CreatedResponseUtil.getCreatedId(response);
    }
  }

  private void createSubGroup(String parentId, String name) {
    GroupRepresentation group = new GroupRepresentation();
    group.setName(name);
    try (Response response = realm().groups().group(parentId).subGroup(group)) {
      assertThat(response.getStatus()).isEqualTo(201);
    }
  }

  private void createClientWithRoles(String clientId, String... roleNames) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(clientId);
    try (Response response = realm().clients().create(client)) {
      String uuid = CreatedResponseUtil.getCreatedId(response);
      for (String roleName : roleNames) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(roleName);
        realm().clients().get(uuid).roles().create(role);
      }
    }
  }
}
