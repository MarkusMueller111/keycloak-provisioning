package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.KeycloakDefaultConnectionConfig;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;

class ClientProvisioningTest extends SharedKeycloakTest {

  @Test
  void createsTheConfiguredClients() {
    withRealmManager(
        realmManager -> {
          realmManager.createOrUpdateClients(List.of(client("system-a"), client("system-b")));

          validateClients(realmManager);
        });
  }

  /** The prefix scopes deletion: dropping a system client from the desired list removes it. */
  @Test
  void deletesSystemClientsThatAreNoLongerConfigured() {
    withRealmManager(
        realmManager -> {
          realmManager.createOrUpdateClients(List.of(client("system-a"), client("system-b")));

          realmManager.createOrUpdateClients(List.of(client("system-a")));
        });

    assertThat(systemClientIds()).containsExactly("system-a");
  }

  @Test
  void leavesClientsOutsideTheSystemPrefixUntouched() {
    adminClient().realm(REALM).clients().create(client("unmanaged")).close();

    withRealmManager(
        realmManager -> realmManager.createOrUpdateClients(List.of(client("system-a"))));

    assertThat(clientIds()).contains("unmanaged", "system-a");
  }

  @Test
  void updatesAnExistingClientInPlace() {
    withRealmManager(
        realmManager -> {
          realmManager.createOrUpdateClients(List.of(client("system-a")));

          ClientRepresentation renamed = client("system-a");
          renamed.setName("renamed");
          realmManager.createOrUpdateClients(List.of(renamed));
        });

    assertThat(findClient("system-a").getName()).isEqualTo("renamed");
  }

  @Test
  void disablesOnlyTheRequestedBuiltInClients() {
    withRealmManager(
        realmManager ->
            realmManager.reconcileBuiltInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT)
                        .enabled(false)
                        .build())));

    assertThat(findClient("account").isEnabled()).isFalse();
    assertThat(findClient("account-console").isEnabled()).isTrue();
  }

  /** Reported before the first write, so a contradiction cannot leave the realm half reconciled. */
  @Test
  void rejectsTheSameBuiltInClientTwice() {
    assertThatThrownBy(
            () ->
                withRealmManager(
                    realmManager ->
                        realmManager.reconcileBuiltInClients(
                            List.of(
                                BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT)
                                    .enabled(false)
                                    .build(),
                                BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT)
                                    .enabled(true)
                                    .build()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account");

    assertThat(findClient("account").isEnabled()).isTrue();
  }

  /** Dropping an entry has to undo it, which a one-way disable could not. */
  @Test
  void reEnablesABuiltInClientThatIsNoLongerDisabled() {
    withRealmManager(
        realmManager ->
            realmManager.reconcileBuiltInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT)
                        .enabled(false)
                        .build())));
    assertThat(findClient("account").isEnabled()).isFalse();

    withRealmManager(realmManager -> realmManager.reconcileBuiltInClients(List.of()));

    assertThat(findClient("account").isEnabled()).isTrue();
  }

  /** Keycloak creates one <realm>-realm client per realm, so it is not a fixed built-in. */
  @Test
  void keycloakProvidedClientCoversEveryBuiltInClientOfAFreshRealm() {
    List<String> builtInIds =
        clientIds().stream().filter(id -> !id.equals(REALM + "-realm")).sorted().toList();

    assertThat(builtInIds)
        .containsExactlyElementsOf(
            java.util.Arrays.stream(KeycloakProvidedClient.values())
                .map(KeycloakProvidedClient::getClientId)
                .sorted()
                .toList());
  }

  /** A custom naming has to reach the deletion fence, not just the ids it mints. */
  @Test
  void scopesDeletionToTheConfiguredPrefix() {
    SystemClientNaming acme = SystemClientNaming.custom("acme-");
    KeycloakDefaultConnectionConfig config =
        new KeycloakDefaultConnectionConfig(
            serverUrl(),
            new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD),
            new KeycloakProvisioningClientConfig("keycloak-provisioning", "secret"),
            acme);

    try (Response response = adminClient().realm(REALM).clients().create(client("system-kept"))) {
      assertThat(response.getStatus()).isEqualTo(201);
    }

    try (DefaultKeycloakConnectionManager connection =
        new DefaultKeycloakConnectionManager(config)) {
      RealmManager realmManager = connection.realmManager(REALM);
      realmManager.createOrUpdateClients(List.of(client("acme-a"), client("acme-b")));
      realmManager.createOrUpdateClients(List.of(client("acme-a")));
    }

    // 'acme-b' fell out of scope and went; 'system-kept' was never in scope under this naming.
    assertThat(clientIds()).contains("acme-a", "system-kept").doesNotContain("acme-b");
  }

  /**
   * Keycloak ignores scope assignments on the client update, so they need their own reconciliation
   * step — both when a client is created and when its scopes change afterwards.
   */
  @Test
  void assignsTheConfiguredClientScopes() {
    ClientRepresentation client = client("system-a");
    client.setDefaultClientScopes(List.of("profile", "email"));
    client.setOptionalClientScopes(List.of("phone"));

    withRealmManager(realmManager -> realmManager.createOrUpdateClients(List.of(client)));

    assertThat(defaultScopeNames("system-a")).containsExactlyInAnyOrder("profile", "email");
    assertThat(optionalScopeNames("system-a")).containsExactly("phone");
  }

  @Test
  void reconcilesClientScopesOfAnExistingClient() {
    ClientRepresentation initial = client("system-a");
    initial.setDefaultClientScopes(List.of("profile", "email"));
    withRealmManager(realmManager -> realmManager.createOrUpdateClients(List.of(initial)));

    ClientRepresentation changed = client("system-a");
    changed.setDefaultClientScopes(List.of("email", "roles"));
    withRealmManager(realmManager -> realmManager.createOrUpdateClients(List.of(changed)));

    assertThat(defaultScopeNames("system-a")).containsExactlyInAnyOrder("email", "roles");
  }

  /** An empty list strips the realm defaults Keycloak assigns on creation. */
  @Test
  void removesEveryClientScopeWhenAnEmptyListIsConfigured() {
    ClientRepresentation client = client("system-a");
    client.setDefaultClientScopes(List.of());
    client.setOptionalClientScopes(List.of());

    withRealmManager(realmManager -> realmManager.createOrUpdateClients(List.of(client)));

    assertThat(defaultScopeNames("system-a")).isEmpty();
    assertThat(optionalScopeNames("system-a")).isEmpty();
  }

  /** Null means "not configured", so Keycloak's realm defaults survive untouched. */
  @Test
  void leavesClientScopesAloneWhenNoneAreConfigured() {
    withRealmManager(
        realmManager -> realmManager.createOrUpdateClients(List.of(client("system-a"))));

    assertThat(defaultScopeNames("system-a")).contains("profile", "email");
  }

  @Test
  void failsOnAClientScopeThatDoesNotExist() {
    ClientRepresentation client = client("system-a");
    client.setDefaultClientScopes(List.of("no-such-scope"));

    assertThatThrownBy(() -> withRealmManager(rm -> rm.createOrUpdateClients(List.of(client))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("no-such-scope");
  }

  /** Bootstrapping one client must not put the realm's other managed clients in scope. */
  @Test
  void createsOrUpdatesASingleClientWithoutTouchingTheOthers() {
    withRealmManager(
        realmManager ->
            realmManager.createOrUpdateClients(List.of(client("system-a"), client("system-b"))));

    withRealmManager(realmManager -> realmManager.createOrUpdateUnmanagedClient(client("app-c")));

    assertThat(systemClientIds()).containsExactlyInAnyOrder("system-a", "system-b");
    assertThat(clientIds()).contains("app-c");
  }

  @Test
  void updatesTheUnmanagedClientInPlace() {
    withRealmManager(realmManager -> realmManager.createOrUpdateUnmanagedClient(client("app-a")));

    ClientRepresentation renamed = client("app-a");
    renamed.setName("renamed");
    withRealmManager(realmManager -> realmManager.createOrUpdateUnmanagedClient(renamed));

    assertThat(findClient("app-a").getName()).isEqualTo("renamed");
  }

  /** The managed namespace has its own reconciliation; mixing the two would fence nothing. */
  @Test
  void rejectsAManagedClientId() {
    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.createOrUpdateUnmanagedClient(client("system-a"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("system-a");
  }

  /**
   * A secret is only reconciled when it is declared: nulling the existing one would read as a
   * change and recreate the client on every single run.
   */
  @Test
  void leavesTheSecretAloneWhenTheClientDeclaresNone() {
    withRealmManager(
        realmManager ->
            realmManager.createOrUpdateClients(List.of(confidentialClient("system-a", "s3cr3t"))));
    String serviceAccountIdBefore = serviceAccountId("system-a");

    ClientRepresentation withoutSecret = confidentialClient("system-a", null);
    withRealmManager(realmManager -> realmManager.createOrUpdateClients(List.of(withoutSecret)));
    withRealmManager(realmManager -> realmManager.createOrUpdateClients(List.of(withoutSecret)));

    assertThat(clientSecret("system-a")).isEqualTo("s3cr3t");
    assertThat(serviceAccountId("system-a")).isEqualTo(serviceAccountIdBefore);
  }

  /** Keycloak drops the secret of a client that turns public, whatever the update carries. */
  @Test
  void dropsTheSecretWhenTheClientTurnsPublic() {
    withRealmManager(
        realmManager ->
            realmManager.createOrUpdateClients(List.of(confidentialClient("system-a", "s3cr3t"))));

    ClientRepresentation asPublic = client("system-a");
    asPublic.setPublicClient(true);
    withRealmManager(realmManager -> realmManager.createOrUpdateClients(List.of(asPublic)));

    assertThat(findClient("system-a").isPublicClient()).isTrue();
    assertThat(findClient("system-a").getSecret()).isNull();
  }

  /** Rotating a secret is an ordinary update, so the client keeps its service account. */
  @Test
  void appliesANewSecretToAnExistingClient() {
    withRealmManager(
        realmManager ->
            realmManager.createOrUpdateClients(List.of(confidentialClient("system-a", "s3cr3t"))));
    String serviceAccountIdBefore = serviceAccountId("system-a");

    withRealmManager(
        realmManager ->
            realmManager.createOrUpdateClients(List.of(confidentialClient("system-a", "r0tat3d"))));

    assertThat(clientSecret("system-a")).isEqualTo("r0tat3d");
    assertThat(serviceAccountId("system-a")).isEqualTo(serviceAccountIdBefore);
  }

  private static ClientRepresentation client(String clientId) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(clientId);
    client.setEnabled(true);
    return client;
  }

  private static ClientRepresentation confidentialClient(String clientId, String secret) {
    ClientRepresentation client = client(clientId);
    client.setPublicClient(false);
    client.setServiceAccountsEnabled(true);
    client.setSecret(secret);
    return client;
  }

  /** Recreating a client takes its service account with it, so a stable id proves it survived. */
  private String serviceAccountId(String clientId) {
    return adminClient()
        .realm(REALM)
        .clients()
        .get(findClient(clientId).getId())
        .getServiceAccountUser()
        .getId();
  }

  private List<String> clientIds() {
    return adminClient().realm(REALM).clients().findAll().stream()
        .map(ClientRepresentation::getClientId)
        .toList();
  }

  private List<String> systemClientIds() {
    return clientIds().stream().filter(id -> id.startsWith("system-")).toList();
  }

  private String clientSecret(String clientId) {
    return adminClient()
        .realm(REALM)
        .clients()
        .get(findClient(clientId).getId())
        .getSecret()
        .getValue();
  }

  private ClientRepresentation findClient(String clientId) {
    return adminClient().realm(REALM).clients().findByClientId(clientId).getFirst();
  }

  private List<String> defaultScopeNames(String clientId) {
    return scopeNames(clientResource(clientId).getDefaultClientScopes());
  }

  private List<String> optionalScopeNames(String clientId) {
    return scopeNames(clientResource(clientId).getOptionalClientScopes());
  }

  private ClientResource clientResource(String clientId) {
    return adminClient().realm(REALM).clients().get(findClient(clientId).getId());
  }

  private static List<String> scopeNames(List<ClientScopeRepresentation> scopes) {
    return scopes.stream().map(ClientScopeRepresentation::getName).toList();
  }
}
