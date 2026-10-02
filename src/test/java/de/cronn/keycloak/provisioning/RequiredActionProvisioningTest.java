package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.cronn.keycloak.provisioning.AuthenticationManagementConfig.RequiredAction;
import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.Change.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;

class RequiredActionProvisioningTest extends SharedKeycloakTest {

  private static final AuthenticationManagementConfig CONFIG =
      AuthenticationManagementConfig.builder()
          .requiredActions(
              List.of(
                  RequiredAction.of(KeycloakProvidedRequiredAction.UPDATE_PASSWORD, true, false),
                  RequiredAction.of(KeycloakProvidedRequiredAction.VERIFY_EMAIL, true, true)))
          .build();

  @Test
  void removesTheRequiredActionsThatAreNotConfigured() {
    withRealmManager(
        rm ->
            assertThat(rm.getAuthenticationManagementManager().configure(CONFIG).changes())
                .extracting(Change::target, Change::name, Change::type)
                .contains(tuple(Target.REQUIRED_ACTION, "TERMS_AND_CONDITIONS", Type.REMOVED)));

    assertThat(requiredActionAliases()).containsExactly("UPDATE_PASSWORD", "VERIFY_EMAIL");
  }

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    withRealmManager(rm -> rm.getAuthenticationManagementManager().configure(CONFIG));

    withRealmManager(
        rm ->
            assertChangesNothing(() -> rm.getAuthenticationManagementManager().configure(CONFIG)));
  }

  private List<String> requiredActionAliases() {
    return adminClient().realm(REALM).flows().getRequiredActions().stream()
        .map(RequiredActionProviderRepresentation::getAlias)
        .toList();
  }
}
