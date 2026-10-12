package de.cronn.keycloak.provisioning.master;

import de.cronn.keycloak.provisioning.AuthenticationManagementConfig;
import de.cronn.keycloak.provisioning.BuiltInClientConfig;
import de.cronn.keycloak.provisioning.EventRetention;
import de.cronn.keycloak.provisioning.KeycloakProvidedRequiredAction;
import de.cronn.keycloak.provisioning.PasswordPolicy;
import de.cronn.keycloak.provisioning.SecurityHeader;
import de.cronn.keycloak.provisioning.SslRequired;
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
  private final boolean deleteTemporaryAdmins;
  private final Duration eventsExpiration;
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
    this.securityHeaders = SecurityHeader.mergeOverDefaults(builder.securityHeaders);
    this.builtInClients = BuiltInClientConfig.mergeOverDefaults(builder.builtInClients);
    this.deleteTemporaryAdmins = builder.deleteTemporaryAdmins;
    this.eventsExpiration =
        builder.eventsExpiration == null
            ? EventRetention.DEFAULT_EVENTS_EXPIRATION
            : builder.eventsExpiration;
    this.adminEventsExpiration =
        builder.adminEventsExpiration == null
            ? EventRetention.DEFAULT_ADMIN_EVENTS_EXPIRATION
            : builder.adminEventsExpiration;
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
   * @see Builder#deleteTemporaryAdmins
   */
  public boolean deleteTemporaryAdmins() {
    return deleteTemporaryAdmins;
  }

  /**
   * @see Builder#eventsExpiration
   */
  public Duration eventsExpiration() {
    return eventsExpiration;
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
   * User-profile attribute declarations. Defaults to Keycloak's built-in attributes (see {@link
   * UserProfileConfig}).
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
    private boolean deleteTemporaryAdmins = true;
    private Duration eventsExpiration;
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

    /**
     * Merged over {@link SecurityHeader#DEFAULT_VALUES} per header; an empty value sends no header.
     */
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
    public Builder deleteTemporaryAdmins(boolean deleteTemporaryAdmins) {
      this.deleteTemporaryAdmins = deleteTemporaryAdmins;
      return this;
    }

    /** Defaults to 90 days; {@link Duration#ZERO} keeps login events forever. */
    public Builder eventsExpiration(Duration eventsExpiration) {
      this.eventsExpiration = eventsExpiration;
      return this;
    }

    /** Defaults to 7 days; {@link Duration#ZERO} keeps admin events forever. */
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
