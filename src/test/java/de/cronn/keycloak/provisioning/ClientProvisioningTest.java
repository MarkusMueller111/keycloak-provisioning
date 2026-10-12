package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.Change.Type;
import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.KeycloakDefaultConnectionConfig;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
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
          ProvisioningResult result = realmManager.provisionManagedClients(List.of(renamed));

          assertThat(result.changes())
              .singleElement()
              .returns(Target.CLIENT, Change::target)
              .returns("system-a", Change::name)
              .returns(Type.UPDATED, Change::type)
              .extracting(Change::diff, as(InstanceOfAssertFactories.STRING))
              .contains("\"renamed\"");
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
  void rejectsDisablingRealmManagement() {
    assertThatThrownBy(
            () ->
                withRealmManager(
                    realmManager ->
                        realmManager.provisionBuiltInClients(
                            List.of(
                                BuiltInClientConfig.forClient(
                                        KeycloakProvidedClient.REALM_MANAGEMENT)
                                    .enabled(false)
                                    .build()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("realm-management");

    assertThat(findClient("broker").isEnabled()).isTrue();
  }

  /** The fallback is the hardened default, not merely enabled. */
  @Test
  void restoresTheHardenedDefaultOfABuiltInClientThatIsNotDeclared() {
    withRealmManager(
        realmManager ->
            realmManager.provisionBuiltInClients(
                List.of(
                    BuiltInClientConfig.forClient(KeycloakProvidedClient.ADMIN_CLI)
                        .enabled(true)
                        .build())));
    assertThat(findClient("admin-cli").isEnabled()).isTrue();

    withRealmManager(realmManager -> realmManager.provisionBuiltInClients(List.of()));

    assertThat(findClient("admin-cli").isEnabled()).isFalse();
    assertThat(findClient("broker").isEnabled()).isFalse();
    assertThat(findClient("account").isStandardFlowEnabled()).isFalse();
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
    ManagedNaming acme = ManagedNaming.custom("acme-", "[Acme] ", "acme-", "acme-");
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
    ClientRepresentation withBadScope = client("system-a");
    withBadScope.setDefaultClientScopes(List.of("no-such-scope"));
    withBadScope.setOptionalClientScopes(List.of());

    assertThatThrownBy(
            () ->
                withRealmManager(
                    rm -> rm.provisionManagedClients(List.of(client("system-b"), withBadScope))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("no-such-scope");

    // Rejected before the first write, so the other client in the same call is untouched.
    assertThat(clientIds()).doesNotContain("system-a", "system-b");
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
  void rejectsWildcardRedirectsAndWebOrigins() {
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

    ClientRepresentation wildcardPostLogout = client("system-c");
    wildcardPostLogout.setAttributes(
        Map.of("post.logout.redirect.uris", "https://app.example.com/logged-out##https://*"));

    assertThatThrownBy(
            () -> withRealmManager(rm -> rm.provisionManagedClients(List.of(wildcardPostLogout))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("post-logout redirect URI");
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
    withRealmManager(
        rm ->
            assertThat(rm.provisionManagedClients(List.of(changed)).changes())
                .extracting(Change::target, Change::name, Change::parent, Change::type)
                .containsExactlyInAnyOrder(
                    tuple(Target.PROTOCOL_MAPPER, "dropped", "system-a", Type.REMOVED),
                    tuple(Target.PROTOCOL_MAPPER, "kept", "system-a", Type.UPDATED)));

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

  @Test
  void restoresKeycloaksDefaultsWhenDeclarationsAreRemoved() {
    String browserFlowId =
        adminClient().realm(REALM).flows().getFlows().stream()
            .filter(flow -> "browser".equals(flow.getAlias()))
            .findFirst()
            .orElseThrow()
            .getId();
    ClientRepresentation declared = client("system-a");
    declared.setDescription("described");
    declared.setRootUrl("https://app.example.com");
    declared.setStandardFlowEnabled(false);
    declared.setRedirectUris(List.of("https://app.example.com/callback"));
    declared.setAttributes(Map.of("pkce.code.challenge.method", "S256"));
    declared.setAuthenticationFlowBindingOverrides(Map.of("browser", browserFlowId));
    withRealmManager(rm -> rm.provisionManagedClients(List.of(declared)));

    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));

    ClientRepresentation actual = findClient("system-a");
    assertThat(actual.getDescription()).isNullOrEmpty();
    assertThat(actual.getRootUrl()).isNullOrEmpty();
    assertThat(actual.isStandardFlowEnabled()).isTrue();
    assertThat(actual.getRedirectUris()).isEmpty();
    assertThat(actual.getWebOrigins()).isEmpty();
    assertThat(actual.getAttributes()).doesNotContainKey("pkce.code.challenge.method");
    assertThat(actual.getAuthenticationFlowBindingOverrides()).isEmpty();
    withRealmManager(
        rm -> assertChangesNothing(() -> rm.provisionManagedClients(List.of(client("system-a")))));
  }

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));

    withRealmManager(
        rm -> assertChangesNothing(() -> rm.provisionManagedClients(List.of(client("system-a")))));
  }

  @Test
  void sendsNoWritesWhenAClientWithScopesAndMappersIsRerunUnchanged() {
    withRealmManager(rm -> rm.provisionManagedClients(List.of(clientWithScopesAndMappers())));

    withRealmManager(
        rm ->
            assertChangesNothing(
                () -> rm.provisionManagedClients(List.of(clientWithScopesAndMappers()))));
  }

  /** Unlike a managed client, this path adds no defaults of its own before creating it. */
  @Test
  void sendsNoWritesWhenAnUnmanagedClientDeclaringNothingIsRerun() {
    ClientRepresentation bare = new ClientRepresentation();
    bare.setClientId("unmanaged-app");
    withRealmManager(rm -> rm.createOrUpdateUnmanagedClient(bare));

    withRealmManager(rm -> assertChangesNothing(() -> rm.createOrUpdateUnmanagedClient(bare)));
  }

  @Test
  void sendsNoWritesWhenBuiltInClientsAreRerunUnchanged() {
    List<BuiltInClientConfig> builtInClients =
        List.of(
            BuiltInClientConfig.forClient(KeycloakProvidedClient.ACCOUNT).enabled(false).build());
    withRealmManager(rm -> rm.provisionBuiltInClients(builtInClients));

    withRealmManager(rm -> assertChangesNothing(() -> rm.provisionBuiltInClients(builtInClients)));
  }

  private static ClientRepresentation clientWithScopesAndMappers() {
    ClientRepresentation client = client("system-a");
    client.setDefaultClientScopes(List.of("profile", "email"));
    client.setOptionalClientScopes(List.of());
    client.setProtocolMappers(
        List.of(
            hardcodedClaim("kept", "one"),
            idTokenClaim("in-id-token", "true"),
            idTokenClaim("not-in-id-token", "false")));
    client.setRedirectUris(
        List.of("https://app.example.com/callback", "https://app.example.com:8443/callback"));
    return client;
  }

  @Test
  void rejectsAProtocolMapperWithoutAProtocol() {
    ProtocolMapperRepresentation withoutProtocol = hardcodedClaim("no-protocol", "x");
    withoutProtocol.setProtocol(null);
    ClientRepresentation withTheMapper = client("system-b");
    withTheMapper.setProtocolMappers(List.of(withoutProtocol));

    assertThatThrownBy(
            () ->
                withRealmManager(
                    rm -> rm.provisionManagedClients(List.of(client("system-a"), withTheMapper))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no-protocol")
        .hasMessageContaining("system-b");

    assertThat(clientIds()).doesNotContain("system-a", "system-b");
  }

  /** A null config means "not declared"; overwriting would wipe what Keycloak holds. */
  @Test
  void keepsAProtocolMapperConfigThatIsNoLongerDeclared() {
    ClientRepresentation withConfig = client("system-a");
    withConfig.setProtocolMappers(List.of(hardcodedClaim("claim", "value")));
    withRealmManager(realmManager -> realmManager.provisionManagedClients(List.of(withConfig)));

    ProtocolMapperRepresentation withoutConfig = hardcodedClaim("claim", "value");
    withoutConfig.setConfig(null);
    ClientRepresentation undeclared = client("system-a");
    undeclared.setProtocolMappers(List.of(withoutConfig));

    withRealmManager(
        realmManager ->
            assertChangesNothing(() -> realmManager.provisionManagedClients(List.of(undeclared))));

    assertThat(mapperConfig("system-a", "claim")).containsEntry("claim.value", "value");
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

  private static ProtocolMapperRepresentation idTokenClaim(String name, String idTokenClaim) {
    ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
    mapper.setName(name);
    mapper.setProtocol("openid-connect");
    mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
    mapper.setConfig(
        Map.of("claim.name", name, "claim.value", "x", "id.token.claim", idTokenClaim));
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

  @Test
  void hardensAnUnmanagedClientLikeAManagedOne() {
    ClientRepresentation wildcard = client("app-wild");
    wildcard.setRedirectUris(List.of("https://app.example.com/*"));

    assertThatThrownBy(() -> withRealmManager(rm -> rm.createOrUpdateUnmanagedClient(wildcard)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("redirect URI");

    ClientRepresentation onlyOptional = client("app-half");
    onlyOptional.setOptionalClientScopes(List.of());

    assertThatThrownBy(() -> withRealmManager(rm -> rm.createOrUpdateUnmanagedClient(onlyOptional)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("optional");

    withRealmManager(rm -> rm.createOrUpdateUnmanagedClient(client("app-scoped")));

    assertThat(findClient("app-scoped").isFullScopeAllowed()).isFalse();
  }

  /** A caller may reuse its representation, so provisioning must not write to it. */
  @Test
  void leavesTheCallersRepresentationAlone() {
    ClientRepresentation declared = client("system-a");

    withRealmManager(rm -> rm.provisionManagedClients(List.of(declared)));

    assertThat(declared.isFullScopeAllowed()).isNull();
    assertThat(declared.isImplicitFlowEnabled()).isNull();
    assertThat(findClient("system-a").isFullScopeAllowed()).isFalse();
  }

  @Test
  void revertsTheRemainingClientFieldsChangedOutOfBand() {
    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));

    ClientRepresentation drifted = findClient("system-a");
    drifted.setAlwaysDisplayInConsole(true);
    drifted.setSurrogateAuthRequired(true);
    drifted.setNodeReRegistrationTimeout(120);
    drifted.setType("drifted-type");
    clientResource("system-a").update(drifted);

    withRealmManager(rm -> rm.provisionManagedClients(List.of(client("system-a"))));

    ClientRepresentation actual = findClient("system-a");
    assertThat(actual.isAlwaysDisplayInConsole()).isFalse();
    assertThat(actual.isSurrogateAuthRequired()).isFalse();
    assertThat(actual.getNodeReRegistrationTimeout()).isEqualTo(-1);
    assertThat(actual.getType()).isNull();
  }

  @Test
  void appliesTheRemainingClientFieldsWhenDeclared() {
    ClientRepresentation declared = client("system-a");
    declared.setAlwaysDisplayInConsole(true);
    declared.setSurrogateAuthRequired(true);
    declared.setNodeReRegistrationTimeout(120);
    declared.setType("my-type");

    withRealmManager(rm -> rm.provisionManagedClients(List.of(declared)));

    ClientRepresentation actual = findClient("system-a");
    assertThat(actual.isAlwaysDisplayInConsole()).isTrue();
    assertThat(actual.isSurrogateAuthRequired()).isTrue();
    assertThat(actual.getNodeReRegistrationTimeout()).isEqualTo(120);
    assertThat(actual.getType()).isEqualTo("my-type");
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
