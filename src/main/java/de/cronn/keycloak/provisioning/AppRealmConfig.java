package de.cronn.keycloak.provisioning;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * Realm-level settings for an application realm, as a plain builder. Only settings Keycloak does
 * not already default sensibly are modelled; anything else — locales, theme, SMTP, password policy,
 * WebAuthn — is left to {@link Builder#realmCustomizer(Consumer)}.
 *
 * <p>The realm's <em>content</em> (roles, groups, clients, scopes) is deliberately not part of
 * this: it belongs to the project, and is provisioned by calling {@link RealmManager} directly.
 */
public final class AppRealmConfig {

  private final String realmName;
  private final String displayName;
  private final SslRequired sslRequired;
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
    this.bruteForceProtected = builder.bruteForceProtected;
    this.eventsEnabled = builder.eventsEnabled;
    this.eventsExpiration = builder.eventsExpiration;
    this.adminEventsEnabled = builder.adminEventsEnabled;
    this.adminEventsExpiration = builder.adminEventsExpiration;
    this.sessionIdleTimeout = builder.sessionIdleTimeout;
    this.sessionMaxLifespan = builder.sessionMaxLifespan;
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

  public boolean bruteForceProtected() {
    return bruteForceProtected;
  }

  public boolean eventsEnabled() {
    return eventsEnabled;
  }

  /** Null leaves Keycloak's own retention in place. */
  public Duration eventsExpiration() {
    return eventsExpiration;
  }

  public boolean adminEventsEnabled() {
    return adminEventsEnabled;
  }

  /** Null leaves Keycloak's own retention in place. */
  public Duration adminEventsExpiration() {
    return adminEventsExpiration;
  }

  public Duration sessionIdleTimeout() {
    return sessionIdleTimeout;
  }

  public Duration sessionMaxLifespan() {
    return sessionMaxLifespan;
  }

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

    public Builder bruteForceProtected(boolean bruteForceProtected) {
      this.bruteForceProtected = bruteForceProtected;
      return this;
    }

    /** Login and other end-user events, Keycloak's {@code events}; it has them off. */
    public Builder eventsEnabled(boolean eventsEnabled) {
      this.eventsEnabled = eventsEnabled;
      return this;
    }

    public Builder eventsExpiration(Duration eventsExpiration) {
      this.eventsExpiration = eventsExpiration;
      return this;
    }

    /** Changes made through the admin API; Keycloak has them off. */
    public Builder adminEventsEnabled(boolean adminEventsEnabled) {
      this.adminEventsEnabled = adminEventsEnabled;
      return this;
    }

    /** Not symmetric with {@link #eventsExpiration}: Keycloak stores this as a realm attribute. */
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
