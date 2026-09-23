package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPConfig;

class UserProfileProvisioningTest extends SharedKeycloakTest {

  @Test
  void keepsTheBuiltInAttributesWhenACustomOneIsDeclared() {
    withRealmManager(
        realmManager ->
            realmManager.configureUserProfile(
                UserProfileConfig.builder()
                    .attributes(
                        List.of(UserProfileConfig.AttributeConfig.builder("department").build()))
                    .build()));

    assertThat(attributeNames())
        .contains("username", "email", "firstName", "lastName", "department");
  }

  @Test
  void dropsABuiltInAttributeKeycloakAllowsToBeRemoved() {
    withRealmManager(
        realmManager ->
            realmManager.configureUserProfile(
                UserProfileConfig.builder()
                    .withoutBuiltInAttributes(KeycloakProvidedUserAttribute.LAST_NAME)
                    .build()));

    assertThat(attributeNames()).doesNotContain("lastName").contains("username", "email");
  }

  /** Pins which attributes Keycloak refuses to drop, so a version bump that changes it is loud. */
  @Test
  void keycloakRejectsAProfileWithoutItsMandatoryAttributes() {
    for (KeycloakProvidedUserAttribute attribute : KeycloakProvidedUserAttribute.values()) {
      if (attribute.isRemovable()) {
        continue;
      }
      assertThatThrownBy(() -> removeAttributeDirectly(attribute.getAttributeName()))
          .describedAs("removing %s", attribute.getAttributeName())
          .isInstanceOf(jakarta.ws.rs.BadRequestException.class);
    }
  }

  private void removeAttributeDirectly(String attributeName) {
    var resource = adminClient().realm(REALM).users().userProfile();
    UPConfig config = resource.getConfiguration();
    config.setAttributes(
        config.getAttributes().stream()
            .filter(attribute -> !attribute.getName().equals(attributeName))
            .toList());
    resource.update(config);
  }

  private List<String> attributeNames() {
    return adminClient()
        .realm(REALM)
        .users()
        .userProfile()
        .getConfiguration()
        .getAttributes()
        .stream()
        .map(UPAttribute::getName)
        .toList();
  }
}
