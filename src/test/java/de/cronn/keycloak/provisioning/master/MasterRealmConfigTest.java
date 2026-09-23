package de.cronn.keycloak.provisioning.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.BuiltInClientConfig;
import de.cronn.keycloak.provisioning.KeycloakProvidedClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class MasterRealmConfigTest {

  @Test
  void keepsTheDefaultsOfClientsThatAreNotDeclared() {
    MasterRealmConfig config =
        builder()
            .builtInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.SECURITY_ADMIN_CONSOLE)
                        .enabled(false)
                        .build()))
            .build();

    assertThat(configFor(config, KeycloakProvidedClient.BROKER).enabled()).isFalse();
    assertThat(configFor(config, KeycloakProvidedClient.ACCOUNT).enabled()).isTrue();
    assertThat(configFor(config, KeycloakProvidedClient.SECURITY_ADMIN_CONSOLE).enabled())
        .isFalse();
  }

  @Test
  void letsADeclaredClientOverrideItsDefault() {
    MasterRealmConfig config =
        builder()
            .builtInClients(
                List.of(BuiltInClientConfig.forClient(KeycloakProvidedClient.BROKER).build()))
            .build();

    assertThat(configFor(config, KeycloakProvidedClient.BROKER).enabled()).isTrue();
  }

  @Test
  void rejectsDisablingRealmManagement() {
    assertThatThrownBy(
            () ->
                builder()
                    .builtInClients(
                        List.of(
                            BuiltInClientConfig.forClient(KeycloakProvidedClient.REALM_MANAGEMENT)
                                .enabled(false)
                                .build()))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realm-management");
  }

  @Test
  void rejectsTheSameClientTwice() {
    assertThatThrownBy(
            () ->
                builder()
                    .builtInClients(
                        List.of(
                            BuiltInClientConfig.forClient(KeycloakProvidedClient.BROKER).build(),
                            BuiltInClientConfig.forClient(KeycloakProvidedClient.BROKER).build()))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("broker");
  }

  private static MasterRealmConfig.Builder builder() {
    return MasterRealmConfig.builder("https://keycloak.example.com", PermanentAdmin.none());
  }

  private static BuiltInClientConfig configFor(
      MasterRealmConfig config, KeycloakProvidedClient client) {
    return config.builtInClients().stream()
        .filter(builtIn -> builtIn.client() == client)
        .findFirst()
        .orElseThrow();
  }
}
