package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

  /** A typo in a later entry must not leave the ones before it registered. */
  @Test
  void writesNothingWhenAProviderIdIsUnknown() {
    AuthenticationManagementConfig withTypo =
        AuthenticationManagementConfig.builder()
            .requiredActions(
                List.of(
                    RequiredAction.of(KeycloakProvidedRequiredAction.UPDATE_PASSWORD, true, false),
                    new RequiredAction("NO_SUCH_ACTION", "Typo", true, false)))
            .build();

    withRealmManager(
        rm ->
            assertThatThrownBy(
                    () ->
                        rm.getAuthenticationManagementManager().provisionRequiredActions(withTypo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_SUCH_ACTION"));

    withRealmManager(rm -> assertThat(requiredActionIds(rm)).contains("CONFIGURE_TOTP"));
  }

  @Test
  void rejectsTheSameRequiredActionTwice() {
    AuthenticationManagementConfig twice =
        AuthenticationManagementConfig.builder()
            .requiredActions(
                List.of(
                    RequiredAction.of(KeycloakProvidedRequiredAction.VERIFY_EMAIL, true, false),
                    RequiredAction.of(KeycloakProvidedRequiredAction.VERIFY_EMAIL, false, false)))
            .build();

    withRealmManager(
        rm ->
            assertThatThrownBy(
                    () -> rm.getAuthenticationManagementManager().provisionRequiredActions(twice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once"));
  }

  private static List<String> requiredActionIds(RealmManager realmManager) {
    return realmManager.getRealmResource().flows().getRequiredActions().stream()
        .map(RequiredActionProviderRepresentation::getProviderId)
        .toList();
  }

  @Test
  void removesTheRequiredActionsThatAreNotConfigured() {
    withRealmManager(
        rm ->
            assertThat(
                    rm.getAuthenticationManagementManager()
                        .provisionRequiredActions(CONFIG)
                        .changes())
                .extracting(Change::target, Change::name, Change::type)
                .contains(tuple(Target.REQUIRED_ACTION, "TERMS_AND_CONDITIONS", Type.REMOVED)));

    assertThat(requiredActionAliases()).containsExactly("UPDATE_PASSWORD", "VERIFY_EMAIL");
  }

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    withRealmManager(
        rm -> rm.getAuthenticationManagementManager().provisionRequiredActions(CONFIG));

    withRealmManager(
        rm ->
            assertChangesNothing(
                () -> rm.getAuthenticationManagementManager().provisionRequiredActions(CONFIG)));
  }

  private List<String> requiredActionAliases() {
    return adminClient().realm(REALM).flows().getRequiredActions().stream()
        .map(RequiredActionProviderRepresentation::getAlias)
        .toList();
  }
}
