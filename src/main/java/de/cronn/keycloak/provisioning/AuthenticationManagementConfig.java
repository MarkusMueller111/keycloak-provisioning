package de.cronn.keycloak.provisioning;

import java.util.List;
import org.apache.commons.lang3.Validate;

/**
 * Which required actions should be registered and enabled/default in the realm. Call {@link
 * #builder()} and add the actions you want. All others will be unregistered.
 */
public record AuthenticationManagementConfig(List<RequiredAction> requiredActions) {

  private AuthenticationManagementConfig(Builder builder) {
    this(builder.requiredActions);
  }

  /** No required actions are configured */
  public static AuthenticationManagementConfig disabled() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * @see Builder#requiredActions
   */
  @Override
  public List<RequiredAction> requiredActions() {
    return requiredActions;
  }

  /** A single required action to enable in the realm. */
  public record RequiredAction(
      String providerId, String displayName, boolean enabled, boolean defaultAction) {

    public RequiredAction {
      Validate.notBlank(providerId);
      if (displayName == null) {
        displayName = providerId;
      }
    }

    public static RequiredAction of(
        KeycloakProvidedRequiredAction action, boolean enabled, boolean defaultAction) {
      return new RequiredAction(
          action.getProviderId(), action.getDisplayName(), enabled, defaultAction);
    }
  }

  public static final class Builder {
    private List<RequiredAction> requiredActions = List.of();

    private Builder() {}

    /**
     * Required actions to enable, in priority order (the first entry gets the highest priority,
     * i.e. runs first).
     */
    public Builder requiredActions(List<RequiredAction> requiredActions) {
      this.requiredActions = List.copyOf(requiredActions);
      return this;
    }

    public AuthenticationManagementConfig build() {
      return new AuthenticationManagementConfig(this);
    }
  }
}
