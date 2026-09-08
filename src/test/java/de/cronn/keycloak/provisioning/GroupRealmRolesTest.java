package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

class GroupRealmRolesTest extends SharedKeycloakTest {

  /**
   * The snapshot pins that a group without roles keeps {@code realmRoles} null rather than an empty
   * list, which downstream validation files rely on.
   */
  @Test
  void reportsRealmRolesPerGroup() {
    createRealmRole("zeta");
    createRealmRole("alpha");
    createGroup("with-roles", List.of("zeta", "alpha"));
    createGroup("without-roles", List.of());

    withRealmManager(this::validateGroups);
  }

  private static GroupRepresentation groupNamed(List<GroupRepresentation> groups, String name) {
    return groups.stream().filter(group -> name.equals(group.getName())).findFirst().orElseThrow();
  }

  private void createRealmRole(String name) {
    RoleRepresentation role = new RoleRepresentation();
    role.setName(name);
    adminClient().realm(REALM).roles().create(role);
  }

  private void createGroup(String name, List<String> realmRoles) {
    GroupRepresentation group = new GroupRepresentation();
    group.setName(name);
    try (Response response = adminClient().realm(REALM).groups().add(group)) {
      assertThat(response.getStatus()).isEqualTo(201);
    }
    if (realmRoles.isEmpty()) {
      return;
    }
    GroupRepresentation created = groupNamed(adminClient().realm(REALM).groups().groups(), name);
    List<RoleRepresentation> roles =
        realmRoles.stream()
            .map(role -> adminClient().realm(REALM).roles().get(role).toRepresentation())
            .toList();
    adminClient().realm(REALM).groups().group(created.getId()).roles().realmLevel().add(roles);
  }
}
