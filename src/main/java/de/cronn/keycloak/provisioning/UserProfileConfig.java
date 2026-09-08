package de.cronn.keycloak.provisioning;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.userprofile.config.UPConfig;

/**
 * The full, authoritative declaration of the realm's user-profile attributes: which are required,
 * who may view/edit them, which group they belong to, and which validation rules apply, per {@link
 * UserProfileRole}. Call {@link #builder()} and declare every attribute the realm should have;
 * attributes not declared here (and not one of the built-in defaults below) are removed from
 * Keycloak on the next provisioning run.
 *
 * <p>{@code username}, {@code email}, {@code firstName} and {@code lastName} are always included
 * via built-in defaults, so a caller can never accidentally remove Keycloak's own required
 * attributes; declare any of them explicitly to override the built-in values.
 */
public final class UserProfileConfig {

  public static final List<AttributeConfig> BUILT_IN_ATTRIBUTES =
      List.of(
          AttributeConfig.defaultUsernameBuilder().build(),
          AttributeConfig.defaultEmailBuilder().build(),
          AttributeConfig.defaultFirstNameBuilder().build(),
          AttributeConfig.defaultLastNameBuilder().build());

  private final List<AttributeConfig> attributes;
  private final Consumer<UPConfig> userProfileCustomizer;

  private UserProfileConfig(Builder builder) {
    this.attributes =
        builder.attributes == null ? BUILT_IN_ATTRIBUTES : List.copyOf(builder.attributes);
    this.userProfileCustomizer =
        builder.userProfileCustomizer == null ? upConfig -> {} : builder.userProfileCustomizer;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * @see Builder#attributes
   */
  public List<AttributeConfig> attributes() {
    return attributes;
  }

  /**
   * @see Builder#userProfileCustomizer
   */
  public Consumer<UPConfig> userProfileCustomizer() {
    return userProfileCustomizer;
  }

  /**
   * Keycloak's two predefined realm roles for administrative vs. ordinary access: an ordinary end
   * user, and an administrative user managed through the admin console. See {@code
   * UPAttributeRequired.roles} and {@code UPAttributePermissions.view}/{@code edit} in Keycloak's
   * user-profile configuration.
   */
  public enum UserProfileRole {
    USER,
    ADMIN;

    String keycloakRoleName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * Declares a single user-profile attribute: display name, which {@code UPGroup} it belongs to
   * (created automatically if it doesn't already exist), which roles it's required for, which roles
   * may view/edit it, and which validation rules apply.
   *
   * <p>Defaults if not set via {@link Builder}: {@code displayName} falls back to {@code
   * attributeName}; {@code requiredFor} defaults to nobody (not required); {@code viewableBy}/
   * {@code editableBy} default to every {@link UserProfileRole} (viewable/editable by everyone);
   * {@code validationRules} defaults to none; {@code group} defaults to {@code null} (no group).
   */
  public record AttributeConfig(
      String attributeName,
      String displayName,
      String group,
      Set<UserProfileRole> requiredFor,
      Set<UserProfileRole> viewableBy,
      Set<UserProfileRole> editableBy,
      List<ValidationRule> validationRules) {

    public AttributeConfig {
      Validate.notBlank(attributeName);
      displayName = displayName == null ? attributeName : displayName;
      // Using null instead of empty set is important for keycloak here!
      requiredFor = requiredFor == null || requiredFor.isEmpty() ? null : Set.copyOf(requiredFor);
      viewableBy =
          viewableBy == null ? EnumSet.allOf(UserProfileRole.class) : Set.copyOf(viewableBy);
      editableBy =
          editableBy == null ? EnumSet.allOf(UserProfileRole.class) : Set.copyOf(editableBy);
      validationRules = validationRules == null ? List.of() : List.copyOf(validationRules);
    }

    public static Builder builder(String attributeName) {
      return new Builder(attributeName);
    }

    public static Builder defaultUsernameBuilder() {
      return builder("username")
          .displayName("${username}")
          .validationRules(
              List.of(
                  new ValidationRule.Length(3, 255),
                  new ValidationRule.Custom("up-username-not-idn-homograph", Map.of()),
                  new ValidationRule.Custom("username-prohibited-characters", Map.of())));
    }

    public static Builder defaultEmailBuilder() {
      return builder("email")
          .displayName("${email}")
          .requiredFor(EnumSet.allOf(UserProfileRole.class))
          .validationRules(
              List.of(
                  new ValidationRule.Custom("email", Map.of()),
                  new ValidationRule.Length(null, 255)));
    }

    public static Builder defaultFirstNameBuilder() {
      return builder("firstName")
          .displayName("${firstName}")
          .validationRules(
              List.of(
                  new ValidationRule.Length(null, 255),
                  new ValidationRule.Custom("person-name-prohibited-characters", Map.of())));
    }

    public static Builder defaultLastNameBuilder() {
      return builder("lastName")
          .displayName("${lastName}")
          .validationRules(
              List.of(
                  new ValidationRule.Length(null, 255),
                  new ValidationRule.Custom("person-name-prohibited-characters", Map.of())));
    }

    public static final class Builder {
      private final String attributeName;
      private String displayName;
      private String group;
      private Set<UserProfileRole> requiredFor;
      private Set<UserProfileRole> viewableBy;
      private Set<UserProfileRole> editableBy;
      private List<ValidationRule> validationRules;

      private Builder(String attributeName) {
        this.attributeName = attributeName;
      }

      public Builder displayName(String displayName) {
        this.displayName = displayName;
        return this;
      }

      /** The {@code UPGroup} name this attribute belongs to; created automatically if missing. */
      public Builder group(String group) {
        this.group = group;
        return this;
      }

      public Builder requiredFor(Set<UserProfileRole> requiredFor) {
        this.requiredFor = requiredFor;
        return this;
      }

      public Builder viewableBy(Set<UserProfileRole> viewableBy) {
        this.viewableBy = viewableBy;
        return this;
      }

      public Builder editableBy(Set<UserProfileRole> editableBy) {
        this.editableBy = editableBy;
        return this;
      }

      public Builder validationRules(List<ValidationRule> validationRules) {
        this.validationRules = validationRules;
        return this;
      }

      public AttributeConfig build() {
        return new AttributeConfig(
            attributeName,
            displayName,
            group,
            requiredFor,
            viewableBy,
            editableBy,
            validationRules);
      }
    }
  }

  /**
   * A single user-profile attribute validation rule, serialized to the {@code Map<String,
   * Map<String, Object>>} shape expected by {@code UPAttribute.validations} (keyed by {@link
   * #ruleId()}).
   */
  public sealed interface ValidationRule {
    String ruleId();

    Map<String, Object> toMap();

    /** Escape hatch for any Keycloak validator id not modeled by a dedicated record below. */
    record Custom(String ruleId, Map<String, Object> config) implements ValidationRule {
      public Custom {
        Validate.notBlank(ruleId);
        config = config == null ? Map.of() : Map.copyOf(config);
      }

      @Override
      public Map<String, Object> toMap() {
        return config;
      }
    }

    record Length(Integer min, Integer max) implements ValidationRule {
      @Override
      public String ruleId() {
        return "length";
      }

      @Override
      public Map<String, Object> toMap() {
        if (min == null) {
          return Map.of("max", Objects.requireNonNull(max));
        } else if (max == null) {
          return Map.of("min", Objects.requireNonNull(min));
        } else {
          return Map.of("min", min, "max", max);
        }
      }
    }

    record Pattern(String regex, String errorMessageKey) implements ValidationRule {
      public Pattern {
        Validate.notBlank(regex);
      }

      @Override
      public String ruleId() {
        return "pattern";
      }

      @Override
      public Map<String, Object> toMap() {
        return Map.of(
            "pattern", regex, "error-message", errorMessageKey == null ? "" : errorMessageKey);
      }
    }
  }

  public static final class Builder {
    private List<AttributeConfig> attributes;
    private Consumer<UPConfig> userProfileCustomizer;

    private Builder() {}

    /**
     * Replaces built-in default attributes with custom attributes list.
     *
     * <p>Use {@link UserProfileConfig#BUILT_IN_ATTRIBUTES} and extend it or build your own list
     * using {@link AttributeConfig#defaultEmailBuilder()} and {@link
     * AttributeConfig#defaultUsernameBuilder()} as a basis.
     */
    public Builder attributes(List<AttributeConfig> attributes) {
      this.attributes = attributes;
      return this;
    }

    /**
     * Applied last, after all other defaults, directly to the {@link UPConfig} that is about to be
     * saved. Use this to set anything not covered by a dedicated option above.
     */
    public Builder userProfileCustomizer(Consumer<UPConfig> userProfileCustomizer) {
      this.userProfileCustomizer = userProfileCustomizer;
      return this;
    }

    public UserProfileConfig build() {
      return new UserProfileConfig(this);
    }
  }
}
