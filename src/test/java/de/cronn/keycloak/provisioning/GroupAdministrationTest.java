package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

class GroupAdministrationTest extends SharedKeycloakTest {

  private static final String STAFF = ManagedNaming.standard().groupName("Staff").value();
  private static final String EDITORS = ManagedNaming.standard().groupName("Editors").value();

  @BeforeEach
  void createRoles() {
    List.of("reader", "writer")
        .forEach(
            name -> {
              RoleRepresentation role = new RoleRepresentation();
              role.setName(name);
              realm().roles().create(role);
            });
  }

  @Test
  void createsAGroupWithItsSubGroupsAndRoles() {
    String id =
        fromAdministration(
            groups ->
                groups.createGroup(
                    GroupConfig.builder("Acme")
                        .realmRoles("reader")
                        .subGroups(GroupConfig.builder("Buyers").realmRoles("writer").build())
                        .build()));

    assertThat(realm().getGroupByPath("/Acme").getId()).isEqualTo(id);
    withRealmManager(this::validateGroups);
  }

  @Test
  void refusesToCreateAGroupThatExists() {
    withAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));

    assertThatThrownBy(
            () ->
                withAdministration(
                    groups -> groups.createGroup(GroupConfig.builder("Acme").build())))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("'/Acme' already exists");
  }

  @Test
  void refusesAGroupNameWithThePrefixAtAnyLevel() {
    GroupConfig prefixedSubGroup =
        GroupConfig.builder("Acme")
            .subGroups(GroupConfig.builder("[System] Buyers").build())
            .build();

    assertThatThrownBy(() -> withAdministration(groups -> groups.createGroup(prefixedSubGroup)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("/Acme/[System] Buyers");

    assertThat(topLevelGroupNames()).isEmpty();
  }

  @Test
  void createsASubGroupBelowAnUnmanagedGroup() {
    String acme =
        fromAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));

    String buyers =
        fromAdministration(
            groups -> groups.createSubGroup(acme, GroupConfig.builder("Buyers").build()));

    assertThat(realm().getGroupByPath("/Acme/Buyers").getId()).isEqualTo(buyers);
  }

  @Test
  void refusesToCreateAGroupWithinAManagedOne() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(GroupConfig.builder(STAFF).build())));
    String staff = realm().getGroupByPath("/" + STAFF).getId();
    String handMade = createSubGroupByHand(staff, "Interns");

    assertThatThrownBy(
            () ->
                withAdministration(
                    groups -> groups.createSubGroup(staff, GroupConfig.builder("Acme").build())))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("managed by provisioning");
    assertThatThrownBy(
            () ->
                withAdministration(
                    groups -> groups.createSubGroup(handMade, GroupConfig.builder("Acme").build())))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("within the managed group '/" + STAFF + "'");
  }

  @Test
  void renamesAGroupAndKeepsItsMembers() {
    String acme =
        fromAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));
    String alice = createUser("alice");
    withAdministration(groups -> groups.addMember(acme, alice));

    withAdministration(
        groups -> groups.updateGroup(acme, GroupConfig.builder("Acme Corp").build()));

    assertThat(realm().getGroupByPath("/Acme Corp").getId()).isEqualTo(acme);
    withRealmManager(this::validateGroups);
  }

  @Test
  void updatesAttributesAndRoleMappings() {
    String acme =
        fromAdministration(
            groups ->
                groups.createGroup(
                    GroupConfig.builder("Acme")
                        .attribute("tenant", "1")
                        .realmRoles("reader")
                        .build()));

    withAdministration(
        groups ->
            groups.updateGroup(
                acme,
                GroupConfig.builder("Acme").attribute("tenant", "2").realmRoles("writer").build()));

    withRealmManager(this::validateGroups);
  }

  @Test
  void leavesTheSubGroupsAloneOnAnUpdate() {
    String acme =
        fromAdministration(
            groups ->
                groups.createGroup(
                    GroupConfig.builder("Acme")
                        .subGroups(GroupConfig.builder("Buyers").build())
                        .build()));

    withAdministration(
        groups -> groups.updateGroup(acme, GroupConfig.builder("Acme").description("x").build()));

    assertThat(realm().getGroupByPath("/Acme/Buyers")).isNotNull();
  }

  @Test
  void replacesTheGroupTree() {
    String acme =
        fromAdministration(
            groups ->
                groups.createGroup(
                    GroupConfig.builder("Acme")
                        .subGroups(
                            GroupConfig.builder("Buyers").build(),
                            GroupConfig.builder("Sellers")
                                .subGroups(GroupConfig.builder("Interns").build())
                                .build())
                        .build()));
    String alice = createUser("alice");
    withAdministration(
        groups -> groups.addMember(realm().getGroupByPath("/Acme/Buyers").getId(), alice));

    withAdministration(
        groups ->
            groups.replaceGroupTree(
                acme,
                GroupConfig.builder("Acme Corp")
                    .subGroups(
                        GroupConfig.builder("Buyers").realmRoles("writer").build(),
                        GroupConfig.builder("Partners")
                            .subGroups(GroupConfig.builder("Resellers").build())
                            .build())
                    .build()));

    withRealmManager(this::validateGroups);
  }

  @Test
  void refusesAPrefixedSubGroupInAReplacedTreeBeforeTheFirstWrite() {
    String acme =
        fromAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));
    GroupConfig prefixed =
        GroupConfig.builder("Acme Corp")
            .subGroups(GroupConfig.builder("[System] Buyers").build())
            .build();

    assertThatThrownBy(() -> withAdministration(groups -> groups.replaceGroupTree(acme, prefixed)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("/Acme Corp/[System] Buyers");

    assertThat(topLevelGroupNames()).containsExactly("Acme");
  }

  @Test
  void refusesToRenameAGroupOntoASibling() {
    withAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));
    String other =
        fromAdministration(groups -> groups.createGroup(GroupConfig.builder("Other").build()));

    assertThatThrownBy(
            () ->
                withAdministration(
                    groups -> groups.updateGroup(other, GroupConfig.builder("Acme").build())))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("'/Acme' already exists");
  }

  @Test
  void refusesAnUpdateDeclaringSubGroups() {
    String acme =
        fromAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));
    GroupConfig withSubGroups =
        GroupConfig.builder("Acme").subGroups(GroupConfig.builder("Buyers").build()).build();

    assertThatThrownBy(() -> withAdministration(groups -> groups.updateGroup(acme, withSubGroups)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replaceGroupTree");
  }

  @Test
  void refusesToUpdateAGroupThatDoesNotExist() {
    assertThatThrownBy(
            () ->
                withAdministration(
                    groups ->
                        groups.updateGroup("no-such-id", GroupConfig.builder("Acme").build())))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("No group with id 'no-such-id'");
  }

  @Test
  void refusesToChangeOrDeleteAManagedGroup() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(GroupConfig.builder(STAFF).build())));
    String staff = realm().getGroupByPath("/" + STAFF).getId();

    assertThatThrownBy(
            () ->
                withAdministration(
                    groups -> groups.updateGroup(staff, GroupConfig.builder("Staff").build())))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("managed by provisioning");
    assertThatThrownBy(
            () ->
                withAdministration(
                    groups -> groups.replaceGroupTree(staff, GroupConfig.builder("Staff").build())))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("managed by provisioning");
    assertThatThrownBy(() -> withAdministration(groups -> groups.deleteGroup(staff)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("managed by provisioning");
  }

  @Test
  void deletesAGroupWithItsSubGroups() {
    String acme =
        fromAdministration(
            groups ->
                groups.createGroup(
                    GroupConfig.builder("Acme")
                        .subGroups(GroupConfig.builder("Buyers").build())
                        .build()));

    withAdministration(groups -> groups.deleteGroup(acme));

    assertThat(topLevelGroupNames()).isEmpty();
  }

  @Test
  void findsGroupsByAttributeAtAnyLevel() {
    withAdministration(
        groups ->
            groups.createGroup(
                GroupConfig.builder("Acme")
                    .attribute("tenant", "42")
                    .subGroups(
                        GroupConfig.builder("Buyers").attribute("tenant", "42").build(),
                        GroupConfig.builder("Sellers").attribute("tenant", "7").build())
                    .build()));

    List<GroupRepresentation> found =
        fromAdministration(groups -> groups.findGroupsByAttribute("tenant", "42"));

    assertThat(found)
        .extracting(GroupRepresentation::getPath)
        .containsExactlyInAnyOrder("/Acme", "/Acme/Buyers");
  }

  @Test
  void findsAGroupById() {
    String acme =
        fromAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));

    withAdministration(
        groups -> {
          assertThat(groups.findGroupById(acme))
              .get()
              .extracting(GroupRepresentation::getPath)
              .isEqualTo("/Acme");
          assertThat(groups.findGroupById("no-such-id")).isEmpty();
        });
  }

  @Test
  void findsAGroupByPathAtAnyLevel() {
    withRealmManager(
        rm ->
            rm.provisionManagedGroups(
                List.of(
                    GroupConfig.builder(STAFF)
                        .subGroups(GroupConfig.builder(EDITORS).build())
                        .build())));

    withAdministration(
        groups -> {
          assertThat(groups.findGroupByPath("/" + STAFF + "/" + EDITORS))
              .get()
              .extracting(GroupRepresentation::getName)
              .isEqualTo(EDITORS);
          assertThat(groups.findGroupByPath("/" + STAFF + "/Nobody")).isEmpty();
        });
  }

  @Test
  void addsAndRemovesMembersOfManagedAndUnmanagedGroups() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(GroupConfig.builder(STAFF).build())));
    String staff = realm().getGroupByPath("/" + STAFF).getId();
    String acme =
        fromAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));
    String alice = createUser("alice");

    withAdministration(
        groups -> {
          groups.addMember(staff, alice);
          groups.addMember(acme, alice);
          groups.removeMember(acme, alice);
        });

    withRealmManager(this::validateGroups);
  }

  @Test
  void refusesToAddAMemberTwiceOrRemoveANonMember() {
    String acme =
        fromAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));
    String alice = createUser("alice");
    withAdministration(groups -> groups.addMember(acme, alice));

    assertThatThrownBy(() -> withAdministration(groups -> groups.addMember(acme, alice)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("already a member");
    withAdministration(groups -> groups.removeMember(acme, alice));
    assertThatThrownBy(() -> withAdministration(groups -> groups.removeMember(acme, alice)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("not a member");
  }

  @Test
  void provisioningLeavesGroupsCreatedOnDemandAlone() {
    withRealmManager(rm -> rm.provisionManagedGroups(List.of(GroupConfig.builder(STAFF).build())));
    withAdministration(groups -> groups.createGroup(GroupConfig.builder("Acme").build()));

    withRealmManager(rm -> rm.provisionManagedGroups(List.of(GroupConfig.builder(STAFF).build())));

    assertThat(topLevelGroupNames()).containsExactlyInAnyOrder(STAFF, "Acme");
  }

  private <T> T fromAdministration(Function<GroupAdministration, T> action) {
    Object[] result = new Object[1];
    withRealmManager(rm -> result[0] = action.apply(new GroupAdministration(rm)));
    @SuppressWarnings("unchecked")
    T typed = (T) result[0];
    return typed;
  }

  private void withAdministration(Consumer<GroupAdministration> action) {
    withRealmManager(rm -> action.accept(new GroupAdministration(rm)));
  }

  private RealmResource realm() {
    return adminClient().realm(REALM);
  }

  private List<String> topLevelGroupNames() {
    return realm().groups().groups().stream().map(GroupRepresentation::getName).toList();
  }

  private String createSubGroupByHand(String parentId, String name) {
    GroupRepresentation group = new GroupRepresentation();
    group.setName(name);
    try (Response response = realm().groups().group(parentId).subGroup(group)) {
      return CreatedResponseUtil.getCreatedId(response);
    }
  }

  private String createUser(String username) {
    UserRepresentation user = new UserRepresentation();
    user.setUsername(username);
    user.setEnabled(true);
    try (Response response = realm().users().create(user)) {
      return CreatedResponseUtil.getCreatedId(response);
    }
  }
}
