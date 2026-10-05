package de.cronn.keycloak.provisioning;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * Realm-level settings for an application realm, as a plain builder. Hardens what Keycloak leaves
 * open: a password policy, a 5 minute idle and 1 hour maximum session, brute-force protection,
 * events, security headers and its built-in clients. Anything not modelled — locales, theme, SMTP,
 * WebAuthn — is left to {@link Builder#realmCustomizer(Consumer)}.
 *
 * <p>The realm's <em>content</em> (roles, groups, clients, scopes) is deliberately not part of
 * this: it belongs to the project, and is provisioned by calling {@link RealmManager} directly.
 */
public final class AppRealmConfig {

  private final String realmName;
  private final String displayName;
  private final SslRequired sslRequired;
  private final String passwordPolicy;
  private final List<BuiltInClientConfig> builtInClients;
  private final boolean bruteForceProtected;
  private final boolean eventsEnabled;
  private final Duration eventsExpiration;
  private final boolean adminEventsEnabled;
  private final Duration adminEventsExpiration;
  private final Duration sessionIdleTimeout;
  private final Duration sessionMaxLifespan;
  private final Duration accessTokenLifespan;
  private final Map<SecurityHeader, String> securityHeaders;
  private final Consumer<RealmRepresentation> realmCustomizer;

  private AppRealmConfig(Builder builder) {
    this.realmName = Validate.notBlank(builder.realmName);
    this.displayName = builder.displayName == null ? builder.realmName : builder.displayName;
    this.sslRequired = builder.sslRequired == null ? SslRequired.EXTERNAL : builder.sslRequired;
    this.passwordPolicy =
        builder.passwordPolicy == null ? PasswordPolicy.DEFAULT : builder.passwordPolicy;
    this.builtInClients = BuiltInClientConfig.mergeOverDefaults(builder.builtInClients);
    this.bruteForceProtected = builder.bruteForceProtected;
    this.eventsEnabled = builder.eventsEnabled;
    this.eventsExpiration =
        builder.eventsExpiration == null
            ? EventRetention.DEFAULT_EVENTS_EXPIRATION
            : builder.eventsExpiration;
    this.adminEventsEnabled = builder.adminEventsEnabled;
    this.adminEventsExpiration =
        builder.adminEventsExpiration == null
            ? EventRetention.DEFAULT_ADMIN_EVENTS_EXPIRATION
            : builder.adminEventsExpiration;
    this.sessionIdleTimeout =
        builder.sessionIdleTimeout == null ? Duration.ofMinutes(5) : builder.sessionIdleTimeout;
    this.sessionMaxLifespan =
        builder.sessionMaxLifespan == null ? Duration.ofHours(1) : builder.sessionMaxLifespan;
    this.accessTokenLifespan = builder.accessTokenLifespan;
    this.securityHeaders =
        builder.securityHeaders == null
            ? Map.copyOf(SecurityHeader.DEFAULT_VALUES)
            : Map.copyOf(builder.securityHeaders);
    this.realmCustomizer = builder.realmCustomizer == null ? realm -> {} : builder.realmCustomizer;
  }

  public static Builder builder(String realmName) {
    return new Builder(realmName);
  }

  public String realmName() {
    return realmName;
  }

  public String displayName() {
    return displayName;
  }

  public SslRequired sslRequired() {
    return sslRequired;
  }

  public String passwordPolicy() {
    return passwordPolicy;
  }

  public List<BuiltInClientConfig> builtInClients() {
    return builtInClients;
  }

  public boolean bruteForceProtected() {
    return bruteForceProtected;
  }

  public boolean eventsEnabled() {
    return eventsEnabled;
  }

  /** {@link Duration#ZERO} means events never expire. */
  public Duration eventsExpiration() {
    return eventsExpiration;
  }

  public boolean adminEventsEnabled() {
    return adminEventsEnabled;
  }

  /** {@link Duration#ZERO} means admin events never expire. */
  public Duration adminEventsExpiration() {
    return adminEventsExpiration;
  }

  public Duration sessionIdleTimeout() {
    return sessionIdleTimeout;
  }

  public Duration sessionMaxLifespan() {
    return sessionMaxLifespan;
  }

  /** Null means Keycloak's default of five minutes. */
  public Duration accessTokenLifespan() {
    return accessTokenLifespan;
  }

  public Map<SecurityHeader, String> securityHeaders() {
    return securityHeaders;
  }

  public Consumer<RealmRepresentation> realmCustomizer() {
    return realmCustomizer;
  }

  public static final class Builder {

    private final String realmName;
    private String displayName;
    private SslRequired sslRequired;
    private String passwordPolicy;
    private List<BuiltInClientConfig> builtInClients;
    private boolean bruteForceProtected = true;
    private boolean eventsEnabled = true;
    private Duration eventsExpiration;
    private boolean adminEventsEnabled = true;
    private Duration adminEventsExpiration;
    private Duration sessionIdleTimeout;
    private Duration sessionMaxLifespan;
    private Duration accessTokenLifespan;
    private Map<SecurityHeader, String> securityHeaders;
    private Consumer<RealmRepresentation> realmCustomizer;

    private Builder(String realmName) {
      this.realmName = realmName;
    }

    /** Defaults to the realm name. */
    public Builder displayName(String displayName) {
      this.displayName = displayName;
      return this;
    }

    public Builder sslRequired(SslRequired sslRequired) {
      this.sslRequired = sslRequired;
      return this;
    }

    /** Merged over {@link BuiltInClientConfig#BUILT_IN_CLIENT_DEFAULTS} per client. */
    public Builder builtInClients(List<BuiltInClientConfig> builtInClients) {
      this.builtInClients = builtInClients;
      return this;
    }

    /** Defaults to {@link PasswordPolicy#DEFAULT}; an empty string opts out. */
    public Builder passwordPolicy(String passwordPolicy) {
      this.passwordPolicy = passwordPolicy;
      return this;
    }

    public Builder bruteForceProtected(boolean bruteForceProtected) {
      this.bruteForceProtected = bruteForceProtected;
      return this;
    }

    /** Login and other end-user events, Keycloak's {@code events}; it has them off. */
    public Builder eventsEnabled(boolean eventsEnabled) {
      this.eventsEnabled = eventsEnabled;
      return this;
    }

    /** Defaults to 90 days; {@link Duration#ZERO} keeps events forever. */
    public Builder eventsExpiration(Duration eventsExpiration) {
      this.eventsExpiration = eventsExpiration;
      return this;
    }

    /** Changes made through the admin API; Keycloak has them off. */
    public Builder adminEventsEnabled(boolean adminEventsEnabled) {
      this.adminEventsEnabled = adminEventsEnabled;
      return this;
    }

    /** Defaults to 7 days; {@link Duration#ZERO} keeps admin events forever. */
    public Builder adminEventsExpiration(Duration adminEventsExpiration) {
      this.adminEventsExpiration = adminEventsExpiration;
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

    public Builder accessTokenLifespan(Duration accessTokenLifespan) {
      this.accessTokenLifespan = accessTokenLifespan;
      return this;
    }

    public Builder securityHeaders(Map<SecurityHeader, String> securityHeaders) {
      this.securityHeaders = securityHeaders;
      return this;
    }

    /** Applied last, so it can override everything above and reach settings not modelled here. */
    public Builder realmCustomizer(Consumer<RealmRepresentation> realmCustomizer) {
      this.realmCustomizer = Objects.requireNonNull(realmCustomizer);
      return this;
    }

    public AppRealmConfig build() {
      return new AppRealmConfig(this);
    }
  }
}
