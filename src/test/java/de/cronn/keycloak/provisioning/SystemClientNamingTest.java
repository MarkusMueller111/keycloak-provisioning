package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SystemClientNamingTest {

  private final SystemClientNaming naming = SystemClientNaming.standard();

  @Test
  void prefixesAClientId() {
    assertThat(naming.clientId("web")).isEqualTo("system-web");
  }

  @Test
  void recognizesAManagedClientId() {
    assertThat(naming.isManaged("system-web")).isTrue();
    assertThat(naming.isManaged("keycloak-provisioning")).isFalse();
  }

  @Test
  void rejectsAnIdThatAlreadyCarriesThePrefix() {
    assertThatThrownBy(() -> naming.clientId("system-web"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("system-");
  }

  @Test
  void appliesACustomPrefix() {
    SystemClientNaming acme = SystemClientNaming.custom("acme-");

    assertThat(acme.clientId("web")).isEqualTo("acme-web");
    assertThat(acme.isManaged("system-web")).isFalse();
  }
}
