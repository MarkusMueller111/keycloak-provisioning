package de.cronn.keycloak.provisioning.master;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.Changes;
import de.cronn.keycloak.provisioning.KeycloakException;
import de.cronn.keycloak.provisioning.ProvisioningResult;
import de.cronn.keycloak.provisioning.RealmManager;
import de.cronn.keycloak.provisioning.SecurityHeader;
import de.cronn.keycloak.provisioning.differ.Declared;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.Validate;
import org.keycloak.admin.client.Keycloak;
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
  public ProvisioningResult provision() {
    ProvisioningResult result =
        configureRealm()
            .plus(realmManager.provisionBuiltInClients(config.builtInClients()))
            .plus(configureRequiredActions())
            .plus(realmManager.provisionDefaultRoleComposites(config.defaultRoleComposites()))
            .plus(realmManager.provisionUserProfile(config.userProfile()));
    Changes changes = new Changes(log, realmManager.getRealmName());
    createPermanentAdminIfAbsent(changes);
    deleteTemporaryAdminUsers(changes);
    return result.plus(changes.result());
  }

  /** The master {@link RealmManager}, for anything beyond what this class covers. */
  public RealmManager getRealmManager() {
    return realmManager;
  }

  private ProvisioningResult configureRealm() {
    ProvisioningResult result = realmManager.createOrUpdateRealm(this::configureRealm);
    // A changed frontend URL changes the token issuer, which the current token no longer matches.
    realmManager.refreshToken();
    return result;
  }

  private void configureRealm(RealmRepresentation realmRepresentation) {
    setAttribute(realmRepresentation, "frontendUrl", config.frontendUrl());
    setAttribute(
        realmRepresentation,
        "adminEventsExpiration",
        String.valueOf(config.adminEventsExpiration().toSeconds()));

    realmRepresentation.setEnabled(true);
    realmRepresentation.setAdminEventsEnabled(true);
    realmRepresentation.setEventsEnabled(true);
    realmRepresentation.setBruteForceProtected(config.bruteForceProtected());
    realmRepresentation.setSslRequired(config.sslRequired().getKeycloakValue());
    realmRepresentation.setPasswordPolicy(config.passwordPolicy());
    realmRepresentation.setSsoSessionIdleTimeout(
        Math.toIntExact(config.sessionIdleTimeout().toSeconds()));
    realmRepresentation.setSsoSessionMaxLifespan(
        Math.toIntExact(config.sessionMaxLifespan().toSeconds()));
    SecurityHeader.applyTo(realmRepresentation, config.securityHeaders());

    realmRepresentation.setSmtpServer(
        Declared.orDefault(
            config.smtp() == null ? null : mapSmtpServer(config.smtp()),
            realmRepresentation.getSmtpServer(),
            Map.of()));

    config.realmCustomizer().accept(realmRepresentation);
  }

  /** Also covers admins created via {@code kc.sh bootstrap-admin}, which carry the same marker. */
  private void deleteTemporaryAdminUsers(Changes changes) {
    if (!config.deleteTemporaryAdminUsers()) {
      return;
    }
    for (UserRepresentation temporaryAdmin : realmManager.getUsersMarkedAsTemporaryAdmin()) {
      changes.removed(Target.USER, temporaryAdmin.getUsername());
      realmManager.deleteUser(temporaryAdmin.getId());
    }
  }

  private void createPermanentAdminIfAbsent(Changes changes) {
    PermanentAdmin permanentAdmin = config.permanentAdmin();
    if (permanentAdmin instanceof PermanentAdmin.None) {
      return;
    }

    // Built once: username and realm roles have to describe the very user we create.
    UserRepresentation user = permanentAdmin.buildUser();
    String username = user.getUsername();
    Optional<UserRepresentation> existing = realmManager.getUserByUsername(username);
    if (existing.isPresent()) {
      assertIsNotATemporaryAdmin(existing.get());
      log.info("Permanent admin '{}' already exists, leaving it untouched", username);
      return;
    }

    changes.added(Target.PERMANENT_ADMIN, username);
    String userId = realmManager.createUser(user);
    try {
      UserResource userResource = realmManager.getUserResource(userId);
      realmManager.addRealmRoles(userResource, PermanentAdmin.realmRolesOf(user), username);
      permanentAdmin.afterCreate(userResource);
    } catch (RuntimeException e) {
      // Roll back, so a half-created admin is not mistaken for an existing one on the next run,
      // which would then delete the temporary admins and leave the realm unreachable.
      log.info("Deleting half-created permanent admin '{}'", username);
      realmManager.deleteUser(userId);
      throw e;
    }
  }

  /**
   * Naming the permanent admin after a temporary one would leave the realm unreachable: it is taken
   * for already provisioned, so it keeps the bootstrap password and never gets the required
   * actions, and {@link #deleteTemporaryAdminUsers(Changes)} then removes it as the last step.
   */
  private void assertIsNotATemporaryAdmin(UserRepresentation existing) {
    boolean isTemporaryAdmin =
        realmManager.getUsersMarkedAsTemporaryAdmin().stream()
            .anyMatch(temporaryAdmin -> temporaryAdmin.getId().equals(existing.getId()));
    if (isTemporaryAdmin) {
      throw new KeycloakException(
          "Permanent admin '"
              + existing.getUsername()
              + "' is one of Keycloak's temporary admins. Give it a username of its own, otherwise"
              + " provisioning would delete the very user it is supposed to keep.");
    }
  }

  private ProvisioningResult configureRequiredActions() {
    return realmManager
        .getAuthenticationManagementManager()
        .provisionRequiredActions(config.authenticationManagement());
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
