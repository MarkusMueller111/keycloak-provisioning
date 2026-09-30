package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.master.MasterRealmConfig;
import de.cronn.keycloak.provisioning.master.MasterRealmProvisioner;
import de.cronn.keycloak.provisioning.master.PermanentAdmin;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Hardening master cannot be undone, so every test here gets its own Keycloak. The frontend URL has
 * to stay the container's own address, since it becomes the token issuer.
 */
class MasterRealmProvisioningTest extends OwnKeycloakTest {

  @Test
  void provisionsTheMasterRealm() {
    provision(configBuilder(PermanentAdmin.none()).build());

    withMaster(this::validateUpdatableKeycloakConfiguration);
  }

  /**
   * The account clients stay enabled: the account console needs both, and an admin-triggered
   * credential reset fails with "Client is not enabled" without them. Only the standard flow on
   * 'account' is turned off.
   */
  @Test
  void disablesBrokerButKeepsTheAccountClients() {
    provision(configBuilder(PermanentAdmin.none()).build());

    assertThat(masterClient("broker").isEnabled()).isFalse();
    assertThat(masterClient("account-console").isEnabled()).isTrue();

    ClientRepresentation account = masterClient("account");
    assertThat(account.isEnabled()).isTrue();
    assertThat(account.isStandardFlowEnabled()).isFalse();
  }

  /**
   * The default without any builtInClients of its own, so it also pins that dropping the former
   * disableAdminCliClient flag did not lose the hardening it applied.
   */
  @Test
  void disablesAdminCliByDefault() {
    provision(
        MasterRealmConfig.builder(serverUrl(), PermanentAdmin.none())
            .deleteTemporaryAdminUsers(false)
            .build());

    withMaster(
        realmManager ->
            assertThat(
                    realmManager
                        .getRealmResource()
                        .clients()
                        .findByClientId("admin-cli")
                        .getFirst()
                        .isEnabled())
                .isFalse());
  }

  @Test
  void disablesTheAccountClientWhenItIsExplicitlyListed() {
    provision(
        configBuilder(PermanentAdmin.none())
            .builtInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT)
                        .enabled(false)
                        .build(),
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT_CONSOLE)
                        .enabled(false)
                        .build(),
                    // This call replaces configBuilder's list, so re-declare the connection these
                    // assertions read back with.
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ADMIN_CLI)
                        .enabled(true)
                        .build()))
            .build());

    assertThat(masterClient("account").isEnabled()).isFalse();
    assertThat(masterClient("account-console").isEnabled()).isFalse();
  }

  @Test
  void createsThePermanentAdminOnceAndLeavesItUntouchedOnReRun() {
    String username = "permanent-admin";
    provision(
        configBuilder(
                new PermanentAdmin.Standard(username, "Str0ng-P4ssw0rd!", "admin@example.com"))
            .build());

    UserRepresentation created = masterUser(username);
    assertThat(realmRoleNames(created)).contains(RealmManager.ADMIN_REALM_ROLE);

    provision(
        configBuilder(
                new PermanentAdmin.Standard(username, "Different-P4ssw0rd!", "new@example.com"))
            .build());

    assertThat(masterUser(username).getId()).isEqualTo(created.getId());
    assertThat(masterUser(username).getEmail()).isEqualTo(created.getEmail());
  }

  /**
   * Without this the realm ends up unreachable: the temporary admin is taken for an already
   * provisioned permanent one, so it keeps the bootstrap password, and deleting the temporary
   * admins then removes the only account left.
   */
  @Test
  void rejectsAPermanentAdminNamedLikeATemporaryOne() {
    assertThatThrownBy(
            () ->
                provision(
                    configBuilder(
                            new PermanentAdmin.Standard(
                                ADMIN_USERNAME, "Str0ng-P4ssw0rd!", "admin@example.com"))
                        .build()))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining(ADMIN_USERNAME)
        .hasMessageContaining("temporary");

    assertThat(masterUser(ADMIN_USERNAME)).isNotNull();
  }

  /** The production default: create the permanent admin before deleting the temporary ones. */
  @Test
  void deletesTheTemporaryAdminsOnlyAfterCreatingThePermanentOne() {
    provision(
        MasterRealmConfig.builder(
                serverUrl(),
                new PermanentAdmin.Standard(
                    "permanent-admin", "Str0ng-P4ssw0rd!", "admin@example.com"))
            .deleteTemporaryAdminUsers(true)
            .builtInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ADMIN_CLI)
                        .enabled(true)
                        .build()))
            .build());

    withMaster(
        realmManager -> {
          assertThat(realmManager.getUserByUsername("permanent-admin")).isPresent();
          assertThat(realmManager.getUsersMarkedAsTemporaryAdmin()).isEmpty();
        });
  }

  /** The full desired set, so Keycloak's own composites go unless they are declared again. */
  @Test
  void replacesTheDefaultRoleCompositesThatAreNotDeclared() {
    provision(
        configBuilder(PermanentAdmin.none())
            .defaultRoleComposites(List.of("offline_access"))
            .build());

    assertThat(defaultRoleCompositeNames())
        .contains("offline_access")
        .doesNotContain("uma_authorization");
  }

  @Test
  void keepsKeycloaksOwnDefaultRoleComposites() {
    provision(configBuilder(PermanentAdmin.none()).build());

    assertThat(defaultRoleCompositeNames()).contains("offline_access", "uma_authorization");
  }

  /**
   * A disabled client is still worth hardening, in case it is ever enabled again by hand. Asserted
   * through the provisioning client, since disabling admin-cli cuts off the connection the other
   * tests here read back with.
   */
  @Test
  void customizesAdminCliWhileDisablingIt() {
    provision(
        MasterRealmConfig.builder(serverUrl(), PermanentAdmin.none())
            .deleteTemporaryAdminUsers(false)
            .builtInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ADMIN_CLI)
                        .enabled(false)
                        .customizer(client -> client.setRedirectUris(List.of()))
                        .build()))
            .build());

    withMaster(
        realmManager -> {
          ClientRepresentation adminCli =
              realmManager.getRealmResource().clients().findByClientId("admin-cli").getFirst();
          assertThat(adminCli.isEnabled()).isFalse();
          assertThat(adminCli.getRedirectUris()).isEmpty();
        });
  }

  @Test
  void permanentAdminNoneCreatesNoUser() {
    long usersBefore = masterUserCount();

    provision(configBuilder(PermanentAdmin.none()).build());

    assertThat(masterUserCount()).isEqualTo(usersBefore);
  }

  @Test
  void appliesTheConfiguredSslRequirement() {
    provision(configBuilder(PermanentAdmin.none()).sslRequired(SslRequired.NONE).build());

    assertThat(masterRealm().getSslRequired()).isEqualTo("none");
  }

  /**
   * Keeps the bootstrap admin usable for the assertions: deleting the temporary admins or disabling
   * admin-cli would cut off the only way back in.
   */
  private MasterRealmConfig.Builder configBuilder(PermanentAdmin permanentAdmin) {
    return MasterRealmConfig.builder(serverUrl(), permanentAdmin)
        .deleteTemporaryAdminUsers(false)
        .builtInClients(
            List.of(
                BuiltInClientConfig.forClient(KeycloakProvidedClient.ADMIN_CLI)
                    .enabled(true)
                    .build()));
  }

  private void provision(MasterRealmConfig config) {
    giveBootstrapAdminAnEmail();
    withMaster(realmManager -> new MasterRealmProvisioner(realmManager, config).provision());
  }

  /**
   * These tests assert over admin-cli, whose password grant cannot run an interactive required
   * action: once provisioning makes email a required user-profile attribute, the email-less
   * bootstrap admin is rejected with "Account is not fully set up" so we give it an email here.
   */
  private void giveBootstrapAdminAnEmail() {
    UserRepresentation admin = masterUser(ADMIN_USERNAME);
    if (admin.getEmail() == null) {
      admin.setEmail("temp-admin@example.com");
      admin.setEmailVerified(true);
      adminClient().realm("master").users().get(admin.getId()).update(admin);
    }
  }

  private void withMaster(Consumer<RealmManager> action) {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      action.accept(connectionManager.realmManager("master"));
    }
  }

  private org.keycloak.representations.idm.RealmRepresentation masterRealm() {
    return adminClient().realm("master").toRepresentation();
  }

  private ClientRepresentation masterClient(String clientId) {
    return adminClient().realm("master").clients().findByClientId(clientId).getFirst();
  }

  private UserRepresentation masterUser(String username) {
    return adminClient().realm("master").users().searchByUsername(username, true).getFirst();
  }

  private long masterUserCount() {
    return adminClient().realm("master").users().count();
  }

  private List<String> defaultRoleCompositeNames() {
    RealmResource master = adminClient().realm("master");
    String defaultRoleName = master.toRepresentation().getDefaultRole().getName();
    return master.roles().get(defaultRoleName).getRealmRoleComposites().stream()
        .map(RoleRepresentation::getName)
        .toList();
  }

  private List<String> realmRoleNames(UserRepresentation user) {
    return adminClient()
        .realm("master")
        .users()
        .get(user.getId())
        .roles()
        .realmLevel()
        .listAll()
        .stream()
        .map(role -> role.getName())
        .toList();
  }
}
