package de.cronn.keycloak.provisioning.master;

import de.cronn.keycloak.provisioning.AuthenticationManagementConfig;
import de.cronn.keycloak.provisioning.BuiltInClientConfig;
import de.cronn.keycloak.provisioning.KeycloakProvidedClient;
import de.cronn.keycloak.provisioning.KeycloakProvidedRequiredAction;
import de.cronn.keycloak.provisioning.PasswordPolicy;
import de.cronn.keycloak.provisioning.SecurityHeader;
import de.cronn.keycloak.provisioning.SslRequired;
import de.cronn.keycloak.provisioning.UserProfileConfig;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
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

  private final String frontendUrl;
  private final PermanentAdmin permanentAdmin;
  private final Smtp smtp;
  private final String passwordPolicy;
  private final Duration sessionIdleTimeout;
  private final Duration sessionMaxLifespan;
  private final boolean bruteForceProtected;
  private final SslRequired sslRequired;
  private final Map<SecurityHeader, String> securityHeaders;
  private final List<BuiltInClientConfig> builtInClients;
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
        builder.passwordPolicy == null ? PasswordPolicy.DEFAULT : builder.passwordPolicy;
    this.sessionIdleTimeout =
        builder.sessionIdleTimeout == null ? Duration.ofMinutes(5) : builder.sessionIdleTimeout;
    this.sessionMaxLifespan =
        builder.sessionMaxLifespan == null ? Duration.ofHours(1) : builder.sessionMaxLifespan;
    this.bruteForceProtected = builder.bruteForceProtected;
    this.sslRequired = builder.sslRequired == null ? SslRequired.EXTERNAL : builder.sslRequired;
    this.securityHeaders =
        builder.securityHeaders == null
            ? Map.copyOf(SecurityHeader.DEFAULT_VALUES)
            : Map.copyOf(builder.securityHeaders);
    this.builtInClients = mergeOverDefaults(builder.builtInClients);
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
            ? AuthenticationManagementConfig.notConfigured()
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

  private static List<BuiltInClientConfig> mergeOverDefaults(List<BuiltInClientConfig> configured) {
    assertBuiltInClientsAreValid(configured);
    Map<KeycloakProvidedClient, BuiltInClientConfig> merged = new LinkedHashMap<>();
    BuiltInClientConfig.BUILT_IN_CLIENT_DEFAULTS.forEach(
        config -> merged.put(config.client(), config));
    if (configured != null) {
      configured.forEach(config -> merged.put(config.client(), config));
    }
    return List.copyOf(merged.values());
  }

  private static void assertBuiltInClientsAreValid(List<BuiltInClientConfig> configured) {
    if (configured == null) {
      return;
    }
    Set<KeycloakProvidedClient> seen = new LinkedHashSet<>();
    for (BuiltInClientConfig config : configured) {
      Validate.isTrue(
          seen.add(config.client()),
          "Client '%s' is configured more than once",
          config.client().getClientId());
      // Keycloak answers a disable with 403, so this would fail mid-provisioning instead.
      Validate.isTrue(
          config.client() != KeycloakProvidedClient.REALM_MANAGEMENT || config.enabled(),
          "Client '%s' cannot be disabled: it backs the realm's admin permissions",
          config.client().getClientId());
    }
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

  public SslRequired sslRequired() {
    return sslRequired;
  }

  public Map<SecurityHeader, String> securityHeaders() {
    return securityHeaders;
  }

  /**
   * @see Builder#builtInClients
   */
  public List<BuiltInClientConfig> builtInClients() {
    return builtInClients;
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
   * Required action configuration. Defaults to {@link
   * AuthenticationManagementConfig#notConfigured()}.
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

  /**
   * Leave both {@code username} and {@code password} null for a relay that needs no credentials.
   */
  public record Smtp(
      String from,
      String host,
      int port,
      boolean sslEnabled,
      boolean startTls,
      String username,
      String password) {

    public Smtp {
      Validate.notBlank(from, "from must not be blank");
      Validate.notBlank(host, "host must not be blank");
      Validate.isTrue(
          (username == null) == (password == null),
          "username and password have to be given together, or neither");
    }
  }

  public static final class Builder {
    private final String frontendUrl;
    private final PermanentAdmin permanentAdmin;
    private Smtp smtp;
    private String passwordPolicy;
    private Duration sessionIdleTimeout;
    private Duration sessionMaxLifespan;
    private boolean bruteForceProtected = true;
    private SslRequired sslRequired;
    private Map<SecurityHeader, String> securityHeaders;
    private List<BuiltInClientConfig> builtInClients;
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

    public Builder sslRequired(SslRequired sslRequired) {
      this.sslRequired = sslRequired;
      return this;
    }

    public Builder securityHeaders(Map<SecurityHeader, String> securityHeaders) {
      this.securityHeaders = securityHeaders;
      return this;
    }

    /**
     * Configuration for Keycloak's own clients, merged over {@link
     * BuiltInClientConfig#BUILT_IN_CLIENT_DEFAULTS} per client: a declared client overrides its
     * default, every other keeps it. A built-in mentioned by neither is provisioned to enabled.
     */
    public Builder builtInClients(List<BuiltInClientConfig> builtInClients) {
      this.builtInClients = builtInClients;
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
     * <p>Independent of whether {@code admin-cli} is disabled: the admin console authenticates
     * through {@code security-admin-console}, so browser login keeps working either way.
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
     * Realm role names to compose into the realm's default role, i.e. granted to every user. The
     * full desired set: Keycloak's own {@code offline_access} and {@code uma_authorization} are the
     * default, and passing a list without them removes them from every user. Use {@link
     * #addDefaultRoleComposites(Collection)} to keep them.
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
