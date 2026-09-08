package de.cronn.keycloak.provisioning.master;

import de.cronn.keycloak.provisioning.AuthenticationManagementConfig;
import de.cronn.keycloak.provisioning.KeycloakProvidedClient;
import de.cronn.keycloak.provisioning.KeycloakProvidedRequiredAction;
import de.cronn.keycloak.provisioning.SecurityHeader;
import de.cronn.keycloak.provisioning.UserProfileConfig;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * Master realm defaults, as a plain builder. Call {@link #builder(String, PermanentAdmin)} with the
 * mandatory frontend URL and permanent admin, override only what you need, and pass the result to
 * {@link MasterRealmProvisioner}.
 */
public final class MasterRealmConfig {

  static final String DEFAULT_PASSWORD_POLICY =
      String.join(
          " and ",
          "length(12)",
          "notContainsUsername",
          "upperCase(1)",
          "lowerCase(1)",
          "digits(1)",
          "specialChars(1)",
          "maxAuthAge(0)");

  private final String frontendUrl;
  private final PermanentAdmin permanentAdmin;
  private final Smtp smtp;
  private final String passwordPolicy;
  private final Duration sessionIdleTimeout;
  private final Duration sessionMaxLifespan;
  private final boolean bruteForceProtected;
  private final Map<SecurityHeader, String> securityHeaders;
  private final Set<String> disabledClientIds;
  private final boolean disableAdminCliClient;
  private final boolean deleteTemporaryAdminUsers;
  private final Duration adminEventsExpiration;
  private final Set<String> defaultRoleComposites;
  private final UserProfileConfig userProfile;
  private final AuthenticationManagementConfig authenticationManagement;
  private final Consumer<RealmRepresentation> realmCustomizer;

  private MasterRealmConfig(Builder builder) {
    this.frontendUrl = Validate.notBlank(builder.frontendUrl);
    this.permanentAdmin = Objects.requireNonNull(builder.permanentAdmin);
    this.smtp = builder.smtp;
    this.passwordPolicy =
        builder.passwordPolicy == null ? DEFAULT_PASSWORD_POLICY : builder.passwordPolicy;
    this.sessionIdleTimeout =
        builder.sessionIdleTimeout == null ? Duration.ofMinutes(5) : builder.sessionIdleTimeout;
    this.sessionMaxLifespan =
        builder.sessionMaxLifespan == null ? Duration.ofHours(1) : builder.sessionMaxLifespan;
    this.bruteForceProtected = builder.bruteForceProtected;
    this.securityHeaders =
        builder.securityHeaders == null
            ? Map.copyOf(SecurityHeader.DEFAULT_VALUES)
            : Map.copyOf(builder.securityHeaders);
    this.disabledClientIds =
        unmodifiableOrdered(
            builder.disabledClientIds == null
                ? defaultDisabledClientIds()
                : builder.disabledClientIds);
    this.disableAdminCliClient = builder.disableAdminCliClient;
    this.deleteTemporaryAdminUsers = builder.deleteTemporaryAdminUsers;
    this.adminEventsExpiration =
        builder.adminEventsExpiration == null ? Duration.ofDays(7) : builder.adminEventsExpiration;
    this.defaultRoleComposites =
        unmodifiableOrdered(
            builder.defaultRoleComposites == null
                ? defaultDefaultRoleComposites()
                : builder.defaultRoleComposites);
    this.userProfile =
        builder.userProfile == null ? UserProfileConfig.builder().build() : builder.userProfile;
    this.authenticationManagement =
        builder.authenticationManagement == null
            ? AuthenticationManagementConfig.disabled()
            : builder.authenticationManagement;
    this.realmCustomizer = builder.realmCustomizer == null ? realm -> {} : builder.realmCustomizer;
    validate();
  }

  /** Set.copyOf would drop the order these sets are declared in, which reaches log output. */
  private static Set<String> unmodifiableOrdered(Collection<String> values) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(values));
  }

  private void validate() {
    if (permanentAdmin instanceof PermanentAdmin.Standard) {
      validateUpdatePasswordIsRegistered();
    }
  }

  /**
   * An empty required-action list leaves Keycloak's defaults (which enable {@code UPDATE_PASSWORD})
   * untouched, so only a non-empty list can drop it.
   */
  private void validateUpdatePasswordIsRegistered() {
    List<AuthenticationManagementConfig.RequiredAction> requiredActions =
        authenticationManagement.requiredActions();
    if (requiredActions.isEmpty()) {
      return;
    }
    String updatePassword = KeycloakProvidedRequiredAction.UPDATE_PASSWORD.getProviderId();
    boolean enabled =
        requiredActions.stream()
            .anyMatch(action -> action.providerId().equals(updatePassword) && action.enabled());
    Validate.isTrue(
        enabled,
        "permanentAdmin is a PermanentAdmin.Standard, whose initial password the user must change"
            + " on first login, but authenticationManagement() does not enable the UPDATE_PASSWORD"
            + " required action. Required actions missing from that list are unregistered, which"
            + " would silently keep the initial password in place forever.");
  }

  /** Client IDs disabled by default unless overridden via {@link Builder#disabledClientIds}. */
  public static Set<String> defaultDisabledClientIds() {
    return new LinkedHashSet<>(
        KeycloakProvidedClient.DISABLED_BY_DEFAULT.stream()
            .map(KeycloakProvidedClient::getClientId)
            .toList());
  }

  /** Default role composites unless overridden via {@link Builder#defaultRoleComposites}. */
  public static Set<String> defaultDefaultRoleComposites() {
    return new LinkedHashSet<>(List.of("offline_access", "uma_authorization"));
  }

  /**
   * {@code permanentAdmin} is a constructor argument so that hardening a realm cannot leave it
   * without administrative access by omission; pass {@link PermanentAdmin#none()} to opt out.
   */
  public static Builder builder(String frontendUrl, PermanentAdmin permanentAdmin) {
    return new Builder(frontendUrl, permanentAdmin);
  }

  public String frontendUrl() {
    return frontendUrl;
  }

  /**
   * @see #builder(String, PermanentAdmin)
   */
  public PermanentAdmin permanentAdmin() {
    return permanentAdmin;
  }

  public Smtp smtp() {
    return smtp;
  }

  public String passwordPolicy() {
    return passwordPolicy;
  }

  public Duration sessionIdleTimeout() {
    return sessionIdleTimeout;
  }

  public Duration sessionMaxLifespan() {
    return sessionMaxLifespan;
  }

  public boolean bruteForceProtected() {
    return bruteForceProtected;
  }

  public Map<SecurityHeader, String> securityHeaders() {
    return securityHeaders;
  }

  /**
   * @see Builder#disabledClientIds
   */
  public Set<String> disabledClientIds() {
    return disabledClientIds;
  }

  /**
   * @see Builder#disableAdminCliClient
   */
  public boolean disableAdminCliClient() {
    return disableAdminCliClient;
  }

  /**
   * @see Builder#deleteTemporaryAdminUsers
   */
  public boolean deleteTemporaryAdminUsers() {
    return deleteTemporaryAdminUsers;
  }

  /**
   * @see Builder#adminEventsExpiration
   */
  public Duration adminEventsExpiration() {
    return adminEventsExpiration;
  }

  /**
   * @see Builder#defaultRoleComposites
   */
  public Set<String> defaultRoleComposites() {
    return defaultRoleComposites;
  }

  /**
   * User-profile attribute declarations. Defaults to just the built-in {@code username}/{@code
   * email} attributes (see {@link UserProfileConfig}).
   */
  public UserProfileConfig userProfile() {
    return userProfile;
  }

  /**
   * Required action configuration. Defaults to {@link AuthenticationManagementConfig#disabled()}.
   */
  public AuthenticationManagementConfig authenticationManagement() {
    return authenticationManagement;
  }

  /**
   * Applied last, after all other defaults, directly to the {@link RealmRepresentation} that is
   * about to be saved. Use this to set anything not covered by a dedicated option above.
   */
  public Consumer<RealmRepresentation> realmCustomizer() {
    return realmCustomizer;
  }

  public record Smtp(
      String from,
      String host,
      int port,
      boolean sslEnabled,
      boolean startTls,
      String username,
      String password) {}

  public static final class Builder {
    private final String frontendUrl;
    private final PermanentAdmin permanentAdmin;
    private Smtp smtp;
    private String passwordPolicy;
    private Duration sessionIdleTimeout;
    private Duration sessionMaxLifespan;
    private boolean bruteForceProtected = true;
    private Map<SecurityHeader, String> securityHeaders;
    private Set<String> disabledClientIds;
    private boolean disableAdminCliClient = true;
    private boolean deleteTemporaryAdminUsers = true;
    private Duration adminEventsExpiration;
    private Set<String> defaultRoleComposites;
    private UserProfileConfig userProfile;
    private AuthenticationManagementConfig authenticationManagement;
    private Consumer<RealmRepresentation> realmCustomizer;

    private Builder(String frontendUrl, PermanentAdmin permanentAdmin) {
      this.frontendUrl = frontendUrl;
      this.permanentAdmin = permanentAdmin;
    }

    public Builder smtp(Smtp smtp) {
      this.smtp = smtp;
      return this;
    }

    public Builder passwordPolicy(String passwordPolicy) {
      this.passwordPolicy = passwordPolicy;
      return this;
    }

    public Builder sessionIdleTimeout(Duration sessionIdleTimeout) {
      this.sessionIdleTimeout = sessionIdleTimeout;
      return this;
    }

    public Builder sessionMaxLifespan(Duration sessionMaxLifespan) {
      this.sessionMaxLifespan = sessionMaxLifespan;
      return this;
    }

    public Builder bruteForceProtected(boolean bruteForceProtected) {
      this.bruteForceProtected = bruteForceProtected;
      return this;
    }

    public Builder securityHeaders(Map<SecurityHeader, String> securityHeaders) {
      this.securityHeaders = securityHeaders;
      return this;
    }

    /** Client IDs that are (to be) disabled in the master realm */
    public Builder disabledClientIds(Collection<String> disabledClientIds) {
      this.disabledClientIds = new LinkedHashSet<>(disabledClientIds);
      return this;
    }

    public Builder addDisabledClientIds(Collection<String> additionalDisabledClientIds) {
      if (this.disabledClientIds == null) {
        this.disabledClientIds = defaultDisabledClientIds();
      }
      this.disabledClientIds.addAll(additionalDisabledClientIds);
      return this;
    }

    public Builder removeDisabledClientId(String exceptedDisabledClientId) {
      if (this.disabledClientIds == null) {
        this.disabledClientIds = defaultDisabledClientIds();
      }
      this.disabledClientIds.remove(exceptedDisabledClientId);
      return this;
    }

    /**
     * Whether {@code admin-cli} is disabled. It enables the direct access grant, i.e. a password
     * grant that bypasses the browser flow including any 2FA configured there. Default: {@code
     * true}.
     *
     * <p>Set to {@code false} when provisioning connects via {@code
     * KeycloakAdminCliConnectionManager} and relies on {@code admin-cli} as a fallback. It is a
     * separate option rather than part of {@link #disabledClientIds} because that makes it a
     * statement about the provisioning connection, not about client hardening.
     */
    public Builder disableAdminCliClient(boolean disableAdminCliClient) {
      this.disableAdminCliClient = disableAdminCliClient;
      return this;
    }

    /**
     * Whether {@link MasterRealmProvisioner#provision()} deletes the users marked {@code
     * is_temporary_admin} as its last step, after the permanent admin was created. Their
     * credentials are typically the well-known {@code KC_BOOTSTRAP_ADMIN_USERNAME}/{@code
     * KC_BOOTSTRAP_ADMIN_PASSWORD} values, so they should not outlive the initial setup. Default:
     * {@code true}.
     *
     * <p>Recovery is possible via {@code kc.sh bootstrap-admin user}, which needs access to the
     * Keycloak container and a password satisfying this realm's {@link #passwordPolicy} — a
     * non-compliant password fails with the misleading message {@code ERROR: Failed to start server
     * in (nonserver) mode}.
     *
     * <p>Independent of {@link #disableAdminCliClient}: the admin console authenticates through
     * {@code security-admin-console}, so browser login keeps working while {@code admin-cli} is
     * disabled.
     */
    public Builder deleteTemporaryAdminUsers(boolean deleteTemporaryAdminUsers) {
      this.deleteTemporaryAdminUsers = deleteTemporaryAdminUsers;
      return this;
    }

    /** How long admin events are retained before Keycloak expires them. */
    public Builder adminEventsExpiration(Duration adminEventsExpiration) {
      this.adminEventsExpiration = adminEventsExpiration;
      return this;
    }

    /**
     * Realm role names to compose into the realm's default role, i.e. granted to every user.
     * Default: empty (opt-in; composing broad roles into the default role is security-sensitive).
     */
    public Builder defaultRoleComposites(Collection<String> defaultRoleComposites) {
      this.defaultRoleComposites = new LinkedHashSet<>(defaultRoleComposites);
      return this;
    }

    public Builder addDefaultRoleComposites(Collection<String> defaultRoleComposites) {
      if (this.defaultRoleComposites == null) {
        this.defaultRoleComposites = defaultDefaultRoleComposites();
      }
      this.defaultRoleComposites.addAll(defaultRoleComposites);
      return this;
    }

    public Builder userProfile(UserProfileConfig userProfile) {
      this.userProfile = userProfile;
      return this;
    }

    public Builder authenticationManagement(
        AuthenticationManagementConfig authenticationManagement) {
      this.authenticationManagement = authenticationManagement;
      return this;
    }

    public Builder realmCustomizer(Consumer<RealmRepresentation> realmCustomizer) {
      this.realmCustomizer = realmCustomizer;
      return this;
    }

    public MasterRealmConfig build() {
      return new MasterRealmConfig(this);
    }
  }
}
