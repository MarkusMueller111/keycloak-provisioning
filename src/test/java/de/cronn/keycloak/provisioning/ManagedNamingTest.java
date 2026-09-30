package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

/** Pure naming logic, so no container needed. */
class ManagedNamingTest {

  private final ManagedNaming naming = ManagedNaming.standard();

  @Test
  void prefixesEachKindOfName() {
    assertThat(naming.clientId("web").value()).isEqualTo("system-web");
    assertThat(naming.roleName("admin").value()).isEqualTo("system-admin");
    assertThat(naming.groupName("Editors").value()).isEqualTo("[System] Editors");
  }

  /** Group names are shown in the admin console, where a technical prefix reads badly. */
  @Test
  void usesAReadablePrefixForGroups() {
    assertThat(naming.groupName("Editors").value()).doesNotStartWith("system-");
  }

  @Test
  void keepsBothFormsOfTheName() {
    ManagedName name = naming.clientId("web");

    assertThat(name.name()).isEqualTo("web");
    assertThat(name.value()).isEqualTo("system-web");
    assertThat(name).hasToString("system-web");
  }

  @Test
  void recognizesManagedNamesPerKind() {
    assertThat(naming.isManaged(client("system-web"))).isTrue();
    assertThat(naming.isManaged(client("web"))).isFalse();
    assertThat(naming.isManaged(group("[System] Editors"))).isTrue();
    assertThat(naming.isManaged(group("Editors"))).isFalse();
    assertThat(naming.isManaged(role("system-admin"))).isTrue();
    assertThat(naming.isManaged(role("admin"))).isFalse();
  }

  /** A group is not a client: the fences must not recognize each other's names. */
  @Test
  void doesNotConfuseTheEntityKinds() {
    assertThat(naming.isManaged(group("system-web"))).isFalse();
    assertThat(naming.isManaged(client("[System] Editors"))).isFalse();
  }

  /** A client is fenced by clientId; its display name must not be consulted. */
  @Test
  void fencesAClientByItsClientIdNotItsDisplayName() {
    ClientRepresentation client = client("system-web");
    client.setName("Some Display Name");

    assertThat(naming.isManaged(client)).isTrue();
  }

  @Test
  void rejectsANameThatAlreadyCarriesThePrefix() {
    assertThatThrownBy(() -> naming.clientId("system-web"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("system-");
  }

  @Test
  void appliesCustomPrefixesPerKind() {
    ManagedNaming custom = ManagedNaming.custom("c-", "g-", "r-", "s-");

    assertThat(custom.clientId("web").value()).isEqualTo("c-web");
    assertThat(custom.groupName("Editors").value()).isEqualTo("g-Editors");
    assertThat(custom.roleName("admin").value()).isEqualTo("r-admin");
    assertThat(custom.isManaged(client("system-web"))).isFalse();
  }

  private static ClientRepresentation client(String clientId) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(clientId);
    return client;
  }

  private static GroupRepresentation group(String name) {
    GroupRepresentation group = new GroupRepresentation();
    group.setName(name);
    return group;
  }

  private static RoleRepresentation role(String name) {
    RoleRepresentation role = new RoleRepresentation();
    role.setName(name);
    return role;
  }
}
