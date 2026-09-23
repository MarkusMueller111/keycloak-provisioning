package de.cronn.keycloak.provisioning.master;

import static de.cronn.keycloak.provisioning.KeycloakProvidedClient.ADMIN_CLI;

import de.cronn.keycloak.provisioning.BuiltInClientConfig;
import de.cronn.keycloak.provisioning.RealmManager;
import de.cronn.keycloak.provisioning.SecurityHeader;
import de.cronn.keycloak.provisioning.UserProfileConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.Validate;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions the Keycloak <em>master</em> realm: realm-level settings (SMTP, password policy,
 * session lifetimes, security headers), and disabling of unused built-in clients.
 *
 * <p>The caller owns the {@link Keycloak} connection (authentication, retries, lifecycle) and hands
 * it in; this class only touches the master realm. Defaults live in {@link MasterRealmConfig} and
 * can be overridden there. For anything not covered by that config, use {@link #getRealmManager()}
 * to reach the underlying Keycloak admin client directly, before or after calling {@link
 * #provision()}.
 */
public class MasterRealmProvisioner {

  private static final Logger log = LoggerFactory.getLogger(MasterRealmProvisioner.class);

  private final MasterRealmConfig config;
  private final RealmManager realmManager;

  public MasterRealmProvisioner(RealmManager realmManager, MasterRealmConfig config) {
    this.realmManager = Objects.requireNonNull(realmManager);
    assertTargetRealmMaster(realmManager);
    this.config = Objects.requireNonNull(config);
  }

  private static void assertTargetRealmMaster(RealmManager realmManager) {
    String realmName = realmManager.getRealmName();
    Validate.isTrue(
        realmName.equals("master"),
        "MasterRealmProvisioner expects realmManager connected to realm 'master' but found realm '%s'"
            .formatted(realmName));
  }

  /** Runs all provisioning steps against the master realm, using the given config. */
  public void provision() {
    configureRealm();
    reconcileBuiltInClients();
    configureRequiredActions();
    configureDefaultRealmRoleComposites();
    configureUserProfile();
    createPermanentAdminIfAbsent();
    deleteTemporaryAdminUsers();
  }

  /** The master {@link RealmManager}, for anything beyond what this class covers. */
  public RealmManager getRealmManager() {
    return realmManager;
  }

  private void configureRealm() {
    RealmResource realm = realmManager.getRealmResource();
    RealmRepresentation realmRepresentation = realm.toRepresentation();

    setAttribute(realmRepresentation, "frontendUrl", config.frontendUrl());
    setAttribute(
        realmRepresentation,
        "adminEventsExpiration",
        String.valueOf(config.adminEventsExpiration().toSeconds()));

    realmRepresentation.setAdminEventsEnabled(true);
    realmRepresentation.setEventsEnabled(true);
    realmRepresentation.setBruteForceProtected(config.bruteForceProtected());
    realmRepresentation.setRevokeRefreshToken(false);
    realmRepresentation.setPasswordPolicy(config.passwordPolicy());
    realmRepresentation.setSsoSessionIdleTimeout(
        Math.toIntExact(config.sessionIdleTimeout().toSeconds()));
    realmRepresentation.setSsoSessionMaxLifespan(
        Math.toIntExact(config.sessionMaxLifespan().toSeconds()));
    configureHttpSecurityHeaders(realmRepresentation, config.securityHeaders());

    if (config.smtp() != null) {
      realmRepresentation.setSmtpServer(mapSmtpServer(config.smtp()));
    }

    config.realmCustomizer().accept(realmRepresentation);

    realm.update(realmRepresentation);
    realmManager.refreshToken();
  }

  private void reconcileBuiltInClients() {
    List<BuiltInClientConfig> builtInClients = new ArrayList<>(config.builtInClients());
    if (config.disableAdminCliClient()) {
      builtInClients.add(BuiltInClientConfig.forClient(ADMIN_CLI).enabled(false).build());
    }
    realmManager.reconcileBuiltInClients(builtInClients);
  }

  /** Also covers admins created via {@code kc.sh bootstrap-admin}, which carry the same marker. */
  private void deleteTemporaryAdminUsers() {
    if (!config.deleteTemporaryAdminUsers()) {
      return;
    }
    for (UserRepresentation temporaryAdmin : realmManager.getUsersMarkedAsTemporaryAdmin()) {
      log.info("Deleting temporary admin user '{}'", temporaryAdmin.getUsername());
      realmManager.deleteUser(temporaryAdmin.getId());
    }
  }

  private void createPermanentAdminIfAbsent() {
    PermanentAdmin permanentAdmin = config.permanentAdmin();
    if (permanentAdmin instanceof PermanentAdmin.None) {
      return;
    }

    // Built once: username and realm roles have to describe the very user we create.
    UserRepresentation user = permanentAdmin.buildUser();
    String username = user.getUsername();
    if (realmManager.getUserByUsername(username).isPresent()) {
      log.info("Permanent admin '{}' already exists, leaving it untouched", username);
      return;
    }

    log.info("Creating permanent admin '{}'", username);
    String userId = realmManager.createUser(user);
    try {
      UserResource userResource = realmManager.getUserResource(userId);
      realmManager.addRealmRoles(userResource, PermanentAdmin.realmRolesOf(user), username);
      permanentAdmin.afterCreate(userResource);
    } catch (RuntimeException e) {
      // Roll back, so a half-created admin is not mistaken for an existing one on the next run,
      // which would then delete the temporary admins and leave the realm unreachable.
      realmManager.deleteUser(userId);
      throw e;
    }
  }

  private void configureUserProfile() {
    UserProfileConfig userProfileConfig = config.userProfile();
    realmManager.configureUserProfile(userProfileConfig);
  }

  private void configureDefaultRealmRoleComposites() {
    realmManager.reconcileDefaultRoleComposites(config.defaultRoleComposites());
  }

  private void configureRequiredActions() {
    realmManager.getAuthenticationManagementManager().configure(config.authenticationManagement());
  }

  private static void setAttribute(
      RealmRepresentation realmRepresentation, String key, String value) {
    Map<String, String> attributes = realmRepresentation.getAttributes();
    if (attributes == null) {
      realmRepresentation.setAttributes(new LinkedHashMap<>(Map.of(key, value)));
    } else {
      Map<String, String> newAttributes = new LinkedHashMap<>(attributes);
      newAttributes.put(key, value);
      realmRepresentation.setAttributes(newAttributes);
    }
  }

  private static void configureHttpSecurityHeaders(
      RealmRepresentation realmRepresentation, Map<SecurityHeader, String> headers) {
    Map<String, String> securityHeaders = realmRepresentation.getBrowserSecurityHeaders();
    if (securityHeaders == null) {
      securityHeaders = new LinkedHashMap<>();
    }

    for (Map.Entry<SecurityHeader, String> header : headers.entrySet()) {
      String newValue = header.getValue();
      String oldValue = securityHeaders.put(header.getKey().getKeycloakName(), newValue);
      if (!Objects.equals(oldValue, newValue)) {
        log.info(
            "Replaced default security header {}\n-    {}\n+    {}",
            header.getKey().name(),
            oldValue,
            newValue);
      }
    }

    realmRepresentation.setBrowserSecurityHeaders(securityHeaders);
  }

  private static Map<String, String> mapSmtpServer(MasterRealmConfig.Smtp smtp) {
    return Map.of(
        "from",
        smtp.from(),
        "host",
        smtp.host(),
        "port",
        String.valueOf(smtp.port()),
        "auth",
        "true",
        "ssl",
        String.valueOf(smtp.sslEnabled()),
        "starttls",
        String.valueOf(smtp.startTls()),
        "user",
        smtp.username(),
        "password",
        smtp.password());
  }
}
