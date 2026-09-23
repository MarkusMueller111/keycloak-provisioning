package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.UserProfileConfig.AttributeConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserProfileConfigTest {

  @Test
  void keepsTheBuiltInAttributesWhenOnlyACustomOneIsDeclared() {
    UserProfileConfig config =
        UserProfileConfig.builder()
            .attributes(List.of(AttributeConfig.builder("department").build()))
            .build();

    assertThat(attributeNames(config))
        .containsExactly("username", "email", "firstName", "lastName", "department");
  }

  @Test
  void letsADeclaredAttributeOverrideItsBuiltInDefault() {
    UserProfileConfig config =
        UserProfileConfig.builder()
            .attributes(List.of(AttributeConfig.builder("email").displayName("E-Mail").build()))
            .build();

    assertThat(attributeNames(config))
        .containsExactly("username", "email", "firstName", "lastName");
    assertThat(attributeFor(config, "email").displayName()).isEqualTo("E-Mail");
    assertThat(attributeFor(config, "username").displayName()).isEqualTo("${username}");
  }

  @Test
  void usesTheBuiltInAttributesWhenNoneAreDeclared() {
    assertThat(attributeNames(UserProfileConfig.builder().build()))
        .containsExactly("username", "email", "firstName", "lastName");
  }

  @Test
  void dropsABuiltInAttribute() {
    UserProfileConfig config =
        UserProfileConfig.builder()
            .withoutBuiltInAttributes(KeycloakProvidedUserAttribute.LAST_NAME)
            .build();

    assertThat(attributeNames(config)).containsExactly("username", "email", "firstName");
  }

  /** Both orders have to mean the same thing, so removal resolves at build() time. */
  @Test
  void dropsABuiltInAttributeRegardlessOfCallOrder() {
    UserProfileConfig removedLast =
        UserProfileConfig.builder()
            .attributes(List.of(AttributeConfig.builder("department").build()))
            .withoutBuiltInAttributes(KeycloakProvidedUserAttribute.LAST_NAME)
            .build();
    UserProfileConfig removedFirst =
        UserProfileConfig.builder()
            .withoutBuiltInAttributes(KeycloakProvidedUserAttribute.LAST_NAME)
            .attributes(List.of(AttributeConfig.builder("department").build()))
            .build();

    assertThat(attributeNames(removedLast)).isEqualTo(attributeNames(removedFirst));
    assertThat(attributeNames(removedLast))
        .containsExactly("username", "email", "firstName", "department");
  }

  @Test
  void refusesToDropAnAttributeKeycloakRequires() {
    assertThatThrownBy(
            () ->
                UserProfileConfig.builder()
                    .withoutBuiltInAttributes(KeycloakProvidedUserAttribute.USERNAME)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("username");
  }

  private static List<String> attributeNames(UserProfileConfig config) {
    return config.attributes().stream().map(AttributeConfig::attributeName).toList();
  }

  private static AttributeConfig attributeFor(UserProfileConfig config, String name) {
    return config.attributes().stream()
        .filter(attribute -> attribute.attributeName().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
