package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPConfig;

class UserProfileProvisioningTest extends SharedKeycloakTest {

  /** An attribute added over REST may carry no permissions, which the diff has to tolerate. */
  @Test
  void toleratesAnExistingAttributeWithoutPermissions() {
    UPConfig upConfig = adminClient().realm(REALM).users().userProfile().getConfiguration();
    UPAttribute bare = new UPAttribute();
    bare.setName("bareAttr");
    bare.setDisplayName("Bare");
    upConfig.getAttributes().add(bare);
    adminClient().realm(REALM).users().userProfile().update(upConfig);

    withRealmManager(
        realmManager -> realmManager.provisionUserProfile(UserProfileConfig.builder().build()));

    assertThat(attributeNames()).doesNotContain("bareAttr");
  }

  @Test
  void keepsTheBuiltInAttributesWhenACustomOneIsDeclared() {
    withRealmManager(
        realmManager ->
            realmManager.provisionUserProfile(
                UserProfileConfig.builder()
                    .attributes(
                        List.of(UserProfileConfig.AttributeConfig.builder("department").build()))
                    .build()));

    assertThat(attributeNames())
        .contains("username", "email", "firstName", "lastName", "department");
  }

  @Test
  void letsOnlyAdminsEditACustomAttributeUnlessEditableBySaysOtherwise() {
    withRealmManager(
        realmManager ->
            realmManager.provisionUserProfile(
                UserProfileConfig.builder()
                    .attributes(
                        List.of(
                            UserProfileConfig.AttributeConfig.builder("department").build(),
                            UserProfileConfig.AttributeConfig.builder("phone")
                                .requiredFor(Set.of(UserProfileConfig.UserProfileRole.USER))
                                .build(),
                            UserProfileConfig.AttributeConfig.builder("nickname")
                                .editableBy(
                                    Set.of(
                                        UserProfileConfig.UserProfileRole.ADMIN,
                                        UserProfileConfig.UserProfileRole.USER))
                                .build()))
                    .build()));

    assertThat(attribute("department").getPermissions().getEdit()).containsExactly("admin");
    assertThat(attribute("department").getPermissions().getView())
        .containsExactlyInAnyOrder("admin", "user");
    assertThat(attribute("phone").getPermissions().getEdit()).containsExactly("admin");
    assertThat(attribute("nickname").getPermissions().getEdit())
        .containsExactlyInAnyOrder("admin", "user");
    assertThat(attribute("firstName").getPermissions().getEdit())
        .containsExactlyInAnyOrder("admin", "user");
  }

  @Test
  void dropsABuiltInAttributeKeycloakAllowsToBeRemoved() {
    withRealmManager(
        realmManager ->
            realmManager.provisionUserProfile(
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

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    UserProfileConfig config =
        UserProfileConfig.builder()
            .attributes(List.of(UserProfileConfig.AttributeConfig.builder("department").build()))
            .withoutBuiltInAttributes(KeycloakProvidedUserAttribute.LAST_NAME)
            .build();
    withRealmManager(realmManager -> realmManager.provisionUserProfile(config));

    withRealmManager(
        realmManager -> assertChangesNothing(() -> realmManager.provisionUserProfile(config)));
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

  /** Keycloak keys validations by rule id, so a repeated one would silently win. */
  @Test
  void rejectsTheSameValidationRuleTwice() {
    assertThatThrownBy(
            () ->
                UserProfileConfig.AttributeConfig.builder("department")
                    .validationRules(
                        List.of(
                            new UserProfileConfig.ValidationRule.Length(1, 10),
                            new UserProfileConfig.ValidationRule.Length(2, 20)))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("department")
        .hasMessageContaining("more than once");
  }

  private UPAttribute attribute(String name) {
    return adminClient().realm(REALM).users().userProfile().getConfiguration().getAttribute(name);
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
