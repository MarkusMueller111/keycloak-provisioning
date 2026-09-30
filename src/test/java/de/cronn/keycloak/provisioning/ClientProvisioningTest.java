package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.KeycloakDefaultConnectionConfig;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

class ClientProvisioningTest extends SharedKeycloakTest {

  @Test
  void createsTheConfiguredClients() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionManagedClients(List.of(client("system-a"), client("system-b")));

          validateClients(realmManager);
        });
  }

  @Test
  void deletesSystemClientsThatAreNoLongerConfigured() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionManagedClients(List.of(client("system-a"), client("system-b")));

          realmManager.provisionManagedClients(List.of(client("system-a")));
        });

    assertThat(systemClientIds()).containsExactly("system-a");
  }

  @Test
  void leavesClientsOutsideTheSystemPrefixUntouched() {
    adminClient().realm(REALM).clients().create(client("unmanaged")).close();

    withRealmManager(
        realmManager -> realmManager.provisionManagedClients(List.of(client("system-a"))));

    assertThat(clientIds()).contains("unmanaged", "system-a");
  }

  @Test
  void updatesAnExistingClientInPlace() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionManagedClients(List.of(client("system-a")));

          ClientRepresentation renamed = client("system-a");
          renamed.setName("renamed");
          realmManager.provisionManagedClients(List.of(renamed));
        });

    assertThat(findClient("system-a").getName()).isEqualTo("renamed");
  }

  @Test
  void disablesOnlyTheRequestedBuiltInClients() {
    withRealmManager(
        realmManager ->
            realmManager.provisionBuiltInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT)
                        .enabled(false)
                        .build())));

    assertThat(findClient("account").isEnabled()).isFalse();
    assertThat(findClient("account-console").isEnabled()).isTrue();
  }

  @Test
  void rejectsTheSameBuiltInClientTwice() {
    assertThatThrownBy(
            () ->
                withRealmManager(
                    realmManager ->
                        realmManager.provisionBuiltInClients(
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

  @Test
  void reEnablesABuiltInClientThatIsNoLongerDisabled() {
    withRealmManager(
        realmManager ->
            realmManager.provisionBuiltInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT)
                        .enabled(false)
                        .build())));
    assertThat(findClient("account").isEnabled()).isFalse();

    withRealmManager(realmManager -> realmManager.provisionBuiltInClients(List.of()));

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
    ManagedNaming acme = ManagedNaming.custom("acme-", "[Acme] ", "acme-");
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
      realmManager.provisionManagedClients(List.of(client("acme-a"), client("acme-b")));
      realmManager.provisionManagedClients(List.of(client("acme-a")));
    }

    // 'acme-b' fell out of scope and went; 'system-kept' was never in scope under this naming.
    assertThat(clientIds()).contains("acme-a", "system-kept").doesNotContain("acme-b");
  }

  /**
   * Keycloak ignores scope assignments on the client update, so they need their own provisioning
   * step — both when a client is created and when its scopes change afterwards.
   */
  @Test
  void assignsTheConfiguredClientScopes() {
    ClientRepresentation client = client("system-a");
    client.setDefaultClientScopes(List.of("profile", "email"));
    client.setOptionalClientScopes(List.of("phone"));

    withRealmManager(realmManager -> realmManager.provisionManagedClients(List.of(client)));

    assertThat(defaultScopeNames("system-a")).containsExactlyInAnyOrder("profile", "email");
    assertThat(optionalScopeNames("system-a")).containsExactly("phone");
  }

  @Test
  void provisionsClientScopesOfAnExistingClient() {
    ClientRepresentation initial = client("system-a");
    initial.setDefaultClientScopes(List.of("profile", "email"));
    initial.setOptionalClientScopes(List.of());
    withRealmManager(realmManager -> realmManager.provisionManagedClients(List.of(initial)));

    ClientRepresentation changed = client("system-a");
    changed.setDefaultClientScopes(List.of("email", "roles"));
    changed.setOptionalClientScopes(List.of());
    withRealmManager(realmManager -> realmManager.provisionManagedClients(List.of(changed)));

    assertThat(defaultScopeNames("system-a")).containsExactlyInAnyOrder("email", "roles");
  }

  /** An empty list strips the realm defaults Keycloak assigns on creation. */
  @Test
  void removesEveryClientScopeWhenAnEmptyListIsConfigured() {
    ClientRepresentation client = client("system-a");
    client.setDefaultClientScopes(List.of());
    client.setOptionalClientScopes(List.of());

    withRealmManager(realmManager -> realmManager.provisionManagedClients(List.of(client)));

    assertThat(defaultScopeNames("system-a")).isEmpty();
    assertThat(optionalScopeNames("system-a")).isEmpty();
  }

  /** Null means "not configured", so Keycloak's realm defaults survive untouched. */
  @Test
  void leavesClientScopesAloneWhenNoneAreConfigured() {
    withRealmManager(
        realmManager -> realmManager.provisionManagedClients(List.of(client("system-a"))));

    assertThat(defaultScopeNames("system-a")).contains("profile", "email");
  }

  @Test
  void failsOnAClientScopeThatDoesNotExist() {
    ClientRepresentation client = client("system-a");
    client.setDefaultClientScopes(List.of("no-such-scope"));
    client.setOptionalClientScopes(List.of());

    assertThatThrownBy(() -> withRealmManager(rm -> rm.provisionManagedClients(List.of(client))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("no-such-scope");
  }

  @Test
  void createsOrUpdatesASingleClientWithoutTouchingTheOthers() {
    withRealmManager(
        realmManager ->
            realmManager.provisionManagedClients(List.of(client("system-a"), client("system-b"))));

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

  /** The managed namespace has its own provisioning; mixing the two would fence nothing. */
  @Test
  void rejectsAManagedClientId() {
    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.createOrUpdateUnmanagedClient(client("system-a"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("system-a");
  }

  /**
   * A secret is only provisioned when it is declared: nulling the existing one would read as a
   * change and recreate the client on every single run.
   */
  @Test
  void leavesTheSecretAloneWhenTheClientDeclaresNone() {
    withRealmManager(
        realmManager ->
            realmManager.provisionManagedClients(
                List.of(confidentialClient("system-a", "s3cr3t"))));
    String serviceAccountIdBefore = serviceAccountId("system-a");

    ClientRepresentation withoutSecret = confidentialClient("system-a", null);
    withRealmManager(realmManager -> realmManager.provisionManagedClients(List.of(withoutSecret)));
    withRealmManager(realmManager -> realmManager.provisionManagedClients(List.of(withoutSecret)));

    assertThat(clientSecret("system-a")).isEqualTo("s3cr3t");
    assertThat(serviceAccountId("system-a")).isEqualTo(serviceAccountIdBefore);
  }

  /** Keycloak drops the secret of a client that turns public, whatever the update carries. */
  @Test
  void dropsTheSecretWhenTheClientTurnsPublic() {
    withRealmManager(
        realmManager ->
            realmManager.provisionManagedClients(
                List.of(confidentialClient("system-a", "s3cr3t"))));

    ClientRepresentation asPublic = client("system-a");
    asPublic.setPublicClient(true);
    withRealmManager(realmManager -> realmManager.provisionManagedClients(List.of(asPublic)));

    assertThat(findClient("system-a").isPublicClient()).isTrue();
    assertThat(findClient("system-a").getSecret()).isNull();
  }

  /** Rotating a secret is an ordinary update, so the client keeps its service account. */
  @Test
  void appliesANewSecretToAnExistingClient() {
    withRealmManager(
        realmManager ->
            realmManager.provisionManagedClients(
                List.of(confidentialClient("system-a", "s3cr3t"))));
    String serviceAccountIdBefore = serviceAccountId("system-a");

    withRealmManager(
        realmManager ->
            realmManager.provisionManagedClients(
                List.of(confidentialClient("system-a", "r0tat3d"))));

    assertThat(clientSecret("system-a")).isEqualTo("r0tat3d");
    assertThat(serviceAccountId("system-a")).isEqualTo(serviceAccountIdBefore);
  }

  /**
   * Without the prefix the client would be created and then never recognized as existing, so the
   * next run would fail with a conflict instead.
   */
  @Test
  void rejectsAClientWhoseIdLacksTheConfiguredPrefix() {
    ClientRepresentation unmanaged = client("unmanaged-app");

    assertThatThrownBy(() -> withRealmManager(rm -> rm.provisionManagedClients(List.of(unmanaged))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("unmanaged-app")
        .hasMessageContaining("system-");

    assertThat(clientIds()).doesNotContain("unmanaged-app");
  }

  @Test
  void provisionsClientFlagsOnUpdate() {
    ClientRepresentation initial = client("system-a");
    initial.setStandardFlowEnabled(true);
    initial.setServiceAccountsEnabled(true);
    withRealmManager(rm -> rm.provisionManagedClients(List.of(initial)));

    ClientRepresentation changed = client("system-a");
    changed.setStandardFlowEnabled(false);
    changed.setServiceAccountsEnabled(false);
    withRealmManager(rm -> rm.provisionManagedClients(List.of(changed)));

    ClientRepresentation actual = findClient("system-a");
    assertThat(actual.isStandardFlowEnabled()).isFalse();
    assertThat(actual.isServiceAccountsEnabled()).isFalse();
  }

  @Test
  void revertsSecurityFlagsChangedOutOfBand() {
    ClientRepresentation declared = client("system-a");
    withRealmManager(rm -> rm.provisionManagedClients(List.of(declared)));

    ClientRepresentation drifted = findClient("system-a");
    drifted.setImplicitFlowEnabled(true);
    drifted.setFullScopeAllowed(true);
    drifted.setConsentRequired(true);
    drifted.setBearerOnly(true);
    adminClient().realm(REALM).clients().get(drifted.getId()).update(drifted);

    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));

    ClientRepresentation actual = findClient("system-a");
    assertThat(actual.isImplicitFlowEnabled()).isFalse();
    assertThat(actual.isFullScopeAllowed()).isFalse();
    assertThat(actual.isConsentRequired()).isFalse();
    assertThat(actual.isBearerOnly()).isFalse();
  }

  /** Keycloak defaults this to true, which would put every role a user holds into the tokens. */
  @Test
  void defaultsFullScopeAllowedToFalse() {
    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));

    assertThat(findClient("system-a").isFullScopeAllowed()).isFalse();
  }

  @Test
  void letsAClientOptBackIntoFullScope() {
    ClientRepresentation client = client("system-a");
    client.setFullScopeAllowed(true);

    withRealmManager(rm -> rm.provisionManagedClients(List.of(client)));

    assertThat(findClient("system-a").isFullScopeAllowed()).isTrue();
  }

  @Test
  void rejectsWildcardRedirectUrisAndWebOrigins() {
    ClientRepresentation wildcardRedirect = client("system-a");
    wildcardRedirect.setRedirectUris(List.of("https://app.example.com/*"));

    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.provisionManagedClients(List.of(wildcardRedirect))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("redirect URI");

    ClientRepresentation wildcardOrigin = client("system-b");
    wildcardOrigin.setWebOrigins(List.of("*"));

    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.provisionManagedClients(List.of(wildcardOrigin))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("web origin");
  }

  /**
   * Safe to provision because a managed client can never be one of Keycloak's own: those carry no
   * prefix, and this method rejects anything without one.
   */
  @Test
  void reEnablesAManagedClientThatWasDisabledOutOfBand() {
    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));
    disableInAdminConsole("system-a");
    assertThat(findClient("system-a").isEnabled()).isFalse();

    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));

    assertThat(findClient("system-a").isEnabled()).isTrue();
  }

  /**
   * Keycloak skips the realm defaults for both lists as soon as either is given, so a half-declared
   * client silently loses its default scopes.
   */
  @Test
  void rejectsAClientThatDeclaresOnlyOneOfItsScopeLists() {
    ClientRepresentation onlyOptional = client("system-a");
    onlyOptional.setOptionalClientScopes(List.of());

    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.provisionManagedClients(List.of(onlyOptional))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("system-a")
        .hasMessageContaining("optional");

    ClientRepresentation onlyDefault = client("system-b");
    onlyDefault.setDefaultClientScopes(List.of("profile"));

    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.provisionManagedClients(List.of(onlyDefault))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("default");
  }

  @Test
  void keepsTheDefaultScopesWhenBothListsAreDeclared() {
    ClientRepresentation client = client("system-a");
    client.setDefaultClientScopes(List.of("profile", "email"));
    client.setOptionalClientScopes(List.of());

    withRealmManager(rm -> rm.provisionManagedClients(List.of(client)));

    assertThat(defaultScopeNames("system-a")).containsExactlyInAnyOrder("profile", "email");
    assertThat(optionalScopeNames("system-a")).isEmpty();
  }

  @Test
  void provisionsTheConfiguredProtocolMappers() {
    ClientRepresentation initial = client("system-a");
    initial.setProtocolMappers(
        List.of(hardcodedClaim("kept", "one"), hardcodedClaim("dropped", "x")));
    withRealmManager(rm -> rm.provisionManagedClients(List.of(initial)));
    assertThat(mapperNames("system-a")).containsExactlyInAnyOrder("kept", "dropped");

    ClientRepresentation changed = client("system-a");
    changed.setProtocolMappers(List.of(hardcodedClaim("kept", "two")));
    withRealmManager(rm -> rm.provisionManagedClients(List.of(changed)));

    assertThat(mapperNames("system-a")).containsExactly("kept");
    assertThat(mapperConfig("system-a", "kept")).containsEntry("claim.value", "two");
  }

  /** Null means "not configured", so mappers Keycloak or a consumer added survive. */
  @Test
  void leavesProtocolMappersAloneWhenNoneAreConfigured() {
    ClientRepresentation withMapper = client("system-a");
    withMapper.setProtocolMappers(List.of(hardcodedClaim("kept", "one")));
    withRealmManager(rm -> rm.provisionManagedClients(List.of(withMapper)));

    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));

    assertThat(mapperNames("system-a")).containsExactly("kept");
  }

  @Test
  void removesEveryProtocolMapperWhenAnEmptyListIsConfigured() {
    ClientRepresentation withMapper = client("system-a");
    withMapper.setProtocolMappers(List.of(hardcodedClaim("kept", "one")));
    withRealmManager(rm -> rm.provisionManagedClients(List.of(withMapper)));

    ClientRepresentation none = client("system-a");
    none.setProtocolMappers(List.of());
    withRealmManager(rm -> rm.provisionManagedClients(List.of(none)));

    assertThat(mapperNames("system-a")).isEmpty();
  }

  private static ProtocolMapperRepresentation hardcodedClaim(String name, String value) {
    ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
    mapper.setName(name);
    mapper.setProtocol("openid-connect");
    mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
    mapper.setConfig(
        new java.util.LinkedHashMap<>(
            Map.of(
                "claim.name", name,
                "claim.value", value,
                "access.token.claim", "true")));
    return mapper;
  }

  private List<String> mapperNames(String clientId) {
    List<ProtocolMapperRepresentation> mappers =
        clientResource(clientId).getProtocolMappers().getMappers();
    return mappers == null
        ? List.of()
        : mappers.stream().map(ProtocolMapperRepresentation::getName).toList();
  }

  private Map<String, String> mapperConfig(String clientId, String mapperName) {
    return clientResource(clientId).getProtocolMappers().getMappers().stream()
        .filter(mapper -> mapperName.equals(mapper.getName()))
        .findFirst()
        .orElseThrow()
        .getConfig();
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

  private void disableInAdminConsole(String clientId) {
    ClientRepresentation client = findClient(clientId);
    client.setEnabled(false);
    adminClient().realm(REALM).clients().get(client.getId()).update(client);
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
