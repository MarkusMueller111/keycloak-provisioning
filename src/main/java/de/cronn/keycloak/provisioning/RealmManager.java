package de.cronn.keycloak.provisioning;

import de.cronn.commons.lang.StreamUtil;
import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.connection.KeycloakConnectionManager;
import de.cronn.keycloak.provisioning.differ.AbstractDiffer;
import de.cronn.keycloak.provisioning.differ.AuthenticationExecutionRepresentationDiffer;
import de.cronn.keycloak.provisioning.differ.ClientRepresentationDiffer;
import de.cronn.keycloak.provisioning.differ.ClientScopeRepresentationDiffer;
import de.cronn.keycloak.provisioning.differ.Declared;
import de.cronn.keycloak.provisioning.differ.Differ;
import de.cronn.keycloak.provisioning.differ.ProtocolMapperDiffer;
import de.cronn.keycloak.provisioning.differ.ToUpdate;
import de.cronn.keycloak.provisioning.differ.UserProfileAttributeDiffer;
import de.cronn.keycloak.provisioning.master.MasterRealmConfig;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.Validate;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientScopesResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.GroupsResource;
import org.keycloak.admin.client.resource.ProtocolMappersResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserProfileResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPAttributeRequired;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.representations.userprofile.config.UPGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Administers a single Keycloak realm reached through a caller-supplied {@link
 * KeycloakConnectionManager}. Does not create or own the connection.
 */
public class RealmManager {

  private static final Logger log = LoggerFactory.getLogger(RealmManager.class);

  public static final String ADMIN_REALM_ROLE = "admin";

  protected final Keycloak keycloak;
  protected final String realmName;
  private final ManagedNaming clientNaming;

  /**
   * Usually obtained via {@link KeycloakConnectionManager#realmManager(String)}, which supplies the
   * naming it was configured with; construct one directly only when managing the {@link Keycloak}
   * connection yourself.
   */
  public RealmManager(Keycloak keycloak, String realmName, ManagedNaming clientNaming) {
    this.keycloak = Objects.requireNonNull(keycloak);
    this.realmName = Objects.requireNonNull(realmName);
    this.clientNaming = Objects.requireNonNull(clientNaming);
  }

  public String getRealmName() {
    return realmName;
  }

  private static ClientRepresentationDiffer getClientsDiffer(
      List<ClientRepresentation> existingClients, List<ClientRepresentation> clients) {
    return new ClientRepresentationDiffer(existingClients, clients);
  }

  static void assertResponseIs201Created(Response response) {
    assertResponseStatus(response, Response.Status.CREATED);
  }

  static void assertResponseIs204NoContent(Response response) {
    assertResponseStatus(response, Response.Status.NO_CONTENT);
  }

  private static void assertResponseStatus(Response response, Response.Status expected) {
    int status = response.getStatus();
    if (status != expected.getStatusCode()) {
      throw new KeycloakException(failureMessage(status));
    }
  }

  static String failureMessage(int status) {
    return "Keycloak REST call failed with HTTP status "
        + status
        + " (enable TRACE logging on de.cronn.keycloak.provisioning to see the response)";
  }

  /**
   * Provisions the realm roles composed into the realm's default role (i.e. granted to every user)
   * to exactly match {@code roleNames}: adds missing ones, removes any composite realm role not
   * listed. {@code roleNames} is treated as the full desired set, the same "declare it all"
   * semantics as {@link MasterRealmConfig#builtInClients()}. Safe to call repeatedly.
   */
  public ProvisioningResult provisionDefaultRoleComposites(Collection<String> roleNames) {
    Changes changes = changes();
    RealmResource realm = getRealmResource();
    RoleRepresentation defaultRole = realm.toRepresentation().getDefaultRole();
    RoleResource defaultRoleResource = realm.roles().get(defaultRole.getName());

    Set<RoleRepresentation> existingComposites = defaultRoleResource.getRealmRoleComposites();
    Set<String> existingCompositeNames =
        existingComposites.stream().map(RoleRepresentation::getName).collect(Collectors.toSet());

    List<RoleRepresentation> toAdd =
        roleNames.stream()
            .filter(name -> !existingCompositeNames.contains(name))
            .map(name -> realm.roles().get(name).toRepresentation())
            .toList();
    if (!toAdd.isEmpty()) {
      toAdd.forEach(role -> changes.added(Target.DEFAULT_ROLE_COMPOSITE, role.getName()));
      defaultRoleResource.addComposites(toAdd);
    }

    List<RoleRepresentation> toRemove =
        existingComposites.stream().filter(role -> !roleNames.contains(role.getName())).toList();
    if (!toRemove.isEmpty()) {
      toRemove.forEach(role -> changes.removed(Target.DEFAULT_ROLE_COMPOSITE, role.getName()));
      defaultRoleResource.deleteComposites(toRemove);
    }
    return changes.result();
  }

  /**
   * Provisions Keycloak's own clients: what {@code builtInClients} declares is applied, every other
   * built-in is reset to the default in {@link BuiltInClientConfig#BUILT_IN_CLIENT_DEFAULTS}, which
   * is not enabled for all of them. Removing an entry therefore restores that default, but does not
   * undo what its {@link BuiltInClientConfig.Builder#customizer customizer} changed. Clients this
   * library or a consumer provisioned are out of scope, those belong to {@link
   * #provisionManagedClients(List)}.
   */
  public ProvisioningResult provisionBuiltInClients(
      Collection<BuiltInClientConfig> builtInClients) {
    Map<KeycloakProvidedClient, BuiltInClientConfig> byClient = new LinkedHashMap<>();
    for (BuiltInClientConfig config : builtInClients) {
      // Before the first write: two entries for one client contradict each other, and reporting
      // that half-way through leaves the realm partly provisioned.
      Validate.isTrue(
          byClient.put(config.client(), config) == null,
          "Client '%s' is configured more than once",
          config.client().getClientId());
    }

    Changes changes = changes();
    ClientsResource clientsResource = getRealmResource().clients();
    Map<String, ClientRepresentation> existingByClientId =
        clientsResource.findAll().stream()
            .collect(Collectors.toMap(ClientRepresentation::getClientId, client -> client));

    for (KeycloakProvidedClient builtIn : KeycloakProvidedClient.values()) {
      ClientRepresentation client = existingByClientId.get(builtIn.getClientId());
      if (client == null) {
        continue;
      }
      BuiltInClientConfig config =
          byClient.getOrDefault(builtIn, BuiltInClientConfig.forClient(builtIn).build());

      String before = AbstractDiffer.toJson(client);
      config.applyTo(client);
      String after = AbstractDiffer.toJson(client);
      if (before.equals(after)) {
        continue;
      }

      changes.updated(
          Target.BUILT_IN_CLIENT,
          builtIn.getClientId(),
          Differ.calculateMultilineDiff(before, after));
      clientsResource.get(client.getId()).update(client);
    }
    return changes.result();
  }

  /**
   * Creates the realm if it is absent, otherwise applies {@code realmCustomizer} and updates it
   * only when that actually changes something. Safe to call repeatedly: an unchanged realm causes
   * no write at all, which is the common case when provisioning runs on every application start.
   */
  public ProvisioningResult createOrUpdateRealm(Consumer<RealmRepresentation> realmCustomizer) {
    Changes changes = changes();
    Optional<RealmRepresentation> existingRealm = getRealmIfExists();
    if (existingRealm.isEmpty()) {
      createRealm(realmCustomizer, changes);
      return changes.result();
    }

    RealmRepresentation realm = existingRealm.get();
    String before = AbstractDiffer.toJson(realm);
    realmCustomizer.accept(realm);
    String after = AbstractDiffer.toJson(realm);

    if (before.equals(after)) {
      log.debug("Realm '{}' is up to date", realmName);
      return changes.result();
    }

    changes.updated(Target.REALM, realmName, Differ.calculateMultilineDiff(before, after));
    getRealmResource().update(realm);
    return changes.result();
  }

  private void createRealm(Consumer<RealmRepresentation> realmCustomizer, Changes changes) {
    changes.added(Target.REALM, realmName);
    RealmRepresentation realm = new RealmRepresentation();
    realm.setRealm(realmName);
    realm.setEnabled(true);
    realmCustomizer.accept(realm);
    keycloak.realms().create(realm);
    // The token was issued before this realm existed and does not cover it yet.
    refreshToken();
  }

  public Optional<RealmRepresentation> getRealmIfExists() {
    return keycloak.realms().findAll().stream()
        .filter(realm -> realmName.equals(realm.getRealm()))
        .collect(StreamUtil.toSingleOptionalElement());
  }

  public ProvisioningResult provisionManagedClients(List<ClientRepresentation> clients) {
    clients.forEach(this::assertClientIsManaged);
    clients.forEach(RealmManager::assertClientScopesAreCompletelySpecified);
    clients.forEach(RealmManager::assertRedirectUrisAreNotWildcards);
    clients.forEach(RealmManager::applySecurityFlagDefaults);
    Predicate<ClientRepresentation> existingClientsPredicate = clientNaming::isManaged;
    return provisionManagedClients(clients, existingClientsPredicate);
  }

  /**
   * Undeclared means off. Any other undeclared client field gets Keycloak's default, which for
   * {@code fullScopeAllowed} is on.
   */
  private static void applySecurityFlagDefaults(ClientRepresentation client) {
    if (client.isFullScopeAllowed() == null) {
      client.setFullScopeAllowed(false);
    }
    if (client.isImplicitFlowEnabled() == null) {
      client.setImplicitFlowEnabled(false);
    }
    if (client.isBearerOnly() == null) {
      client.setBearerOnly(false);
    }
    if (client.isConsentRequired() == null) {
      client.setConsentRequired(false);
    }
  }

  /** A wildcard lets any URI under it receive the authorization code. Keycloak accepts them. */
  private static void assertRedirectUrisAreNotWildcards(ClientRepresentation client) {
    assertNoWildcards(client.getClientId(), "redirect URI", client.getRedirectUris());
    assertNoWildcards(client.getClientId(), "web origin", client.getWebOrigins());
  }

  private static void assertNoWildcards(String clientId, String what, List<String> values) {
    if (values == null) {
      return;
    }
    for (String value : values) {
      if (value.contains("*")) {
        throw new KeycloakException(
            "Client '"
                + clientId
                + "' declares the wildcard "
                + what
                + " '"
                + value
                + "'. List the exact values instead.");
      }
    }
  }

  /**
   * Provisions one client outside the managed namespace, scoped to its own id so nothing else can
   * be deleted. For clients this library creates but does not own the lifecycle of. E.g. the
   * provisioning client itself, which must stay clear of {@link #provisionManagedClients} so a
   * consumer's own provisioning does not delete the identity it is connected as.
   */
  public ProvisioningResult createOrUpdateUnmanagedClient(ClientRepresentation client) {
    String clientId = client.getClientId();
    Validate.isTrue(
        !clientNaming.isManaged(client),
        "Client '%s' carries the managed prefix '%s' and belongs in provisionManagedClients",
        clientId,
        clientNaming.prefix(ManagedNaming.ManagedEntity.CLIENT));
    return provisionManagedClients(
        List.of(client), existing -> clientId.equals(existing.getClientId()));
  }

  /**
   * Without the prefix a client would be created but never recognized as existing again, so the
   * next run would try to create it a second time and fail with a conflict.
   */
  private void assertClientIsManaged(ClientRepresentation client) {
    String clientId = client.getClientId();
    if (!clientNaming.isManaged(client)) {
      throw new KeycloakException(
          "Client '"
              + clientId
              + "' cannot be provisioned: its id must carry the configured prefix '"
              + clientNaming.prefix(ManagedNaming.ManagedEntity.CLIENT)
              + "'. Use ManagedNaming.clientId(...) to build it, or configure a naming whose"
              + " prefix matches.");
    }
  }

  /**
   * Keycloak treats scope assignment on a new client as all-or-nothing: specifying either list
   * makes it skip the realm's default scopes for <em>both</em>, so a client that only sets the
   * optional list silently ends up with no default scopes either. Declaring one without the other
   * is therefore always a mistake, and provisioning cannot repair it — a null list means "leave as
   * is", which is indistinguishable from "was never assigned".
   */
  private static void assertClientScopesAreCompletelySpecified(ClientRepresentation client) {
    boolean defaultScopesGiven = client.getDefaultClientScopes() != null;
    boolean optionalScopesGiven = client.getOptionalClientScopes() != null;
    if (defaultScopesGiven == optionalScopesGiven) {
      return;
    }
    throw new KeycloakException(
        "Client '"
            + client.getClientId()
            + "' declares only its "
            + (defaultScopesGiven ? "default" : "optional")
            + " client scopes. Keycloak then assigns neither list's realm defaults, so declare both"
            + " (an empty list assigns none) or neither (Keycloak's defaults apply).");
  }

  private ProvisioningResult provisionManagedClients(
      List<ClientRepresentation> clients,
      Predicate<ClientRepresentation> existingClientsToConsiderPredicate) {
    Changes changes = changes();
    List<ClientRepresentation> existingClients =
        getRealmResource().clients().findAll().stream()
            .filter(existingClientsToConsiderPredicate)
            .toList();

    ClientRepresentationDiffer clientDiffer = getClientsDiffer(existingClients, clients);
    clientDiffer.getElementsToAdd().forEach(client -> addClient(client, changes));
    clientDiffer.getElementsToDelete().forEach(client -> deleteClient(client, changes));
    clientDiffer.getElementsToUpdate().forEach(update -> updateClient(update, changes));

    provisionClientScopeAssignments(clients, changes);
    provisionClientProtocolMappers(clients, changes);
    return changes.result();
  }

  /**
   * Provisions the client scopes carrying the configured prefix. Keycloak's own scopes carry none
   * and are never touched, which matters because deleting {@code profile} or {@code roles} strips
   * claims from every token in the realm while login keeps working.
   *
   * <p>A scope's own update ignores its protocol mappers, so those are provisioned separately. A
   * {@code null} mapper list on a scope leaves its mappers alone.
   */
  public ProvisioningResult provisionManagedClientScopes(
      List<ClientScopeRepresentation> clientScopes) {
    clientScopes.forEach(this::assertClientScopeIsManaged);
    Changes changes = changes();

    ClientScopesResource scopesResource = getRealmResource().clientScopes();
    List<ClientScopeRepresentation> existingScopes =
        scopesResource.findAll().stream().filter(clientNaming::isManaged).toList();

    ClientScopeRepresentationDiffer differ =
        new ClientScopeRepresentationDiffer(existingScopes, clientScopes);

    for (ClientScopeRepresentation scope : differ.getElementsToDelete()) {
      changes.removed(Target.CLIENT_SCOPE, scope.getName());
      scopesResource.get(scope.getId()).remove();
    }
    for (ClientScopeRepresentation scope : differ.getElementsToAdd()) {
      changes.added(Target.CLIENT_SCOPE, scope.getName());
      try (Response response = scopesResource.create(scope)) {
        assertResponseIs201Created(response);
      }
    }
    for (ToUpdate<ClientScopeRepresentation> update : differ.getElementsToUpdate()) {
      ClientScopeRepresentation scope = update.newState();
      changes.updated(Target.CLIENT_SCOPE, scope.getName(), update.multiLineDiff());
      scopesResource.get(scope.getId()).update(scope);
    }

    provisionClientScopeProtocolMappers(clientScopes, changes);
    return changes.result();
  }

  private void assertClientScopeIsManaged(ClientScopeRepresentation clientScope) {
    if (!clientNaming.isManaged(clientScope)) {
      throw new KeycloakException(
          "Client scope '"
              + clientScope.getName()
              + "' cannot be provisioned: its name must carry the configured prefix '"
              + clientNaming.prefix(ManagedNaming.ManagedEntity.CLIENT_SCOPE)
              + "'.");
    }
  }

  private void provisionClientScopeProtocolMappers(
      List<ClientScopeRepresentation> clientScopes, Changes changes) {
    Map<String, String> scopeIdsByName =
        getRealmResource().clientScopes().findAll().stream()
            .collect(
                Collectors.toMap(
                    ClientScopeRepresentation::getName, ClientScopeRepresentation::getId));

    for (ClientScopeRepresentation scope : clientScopes) {
      if (scope.getProtocolMappers() == null) {
        continue;
      }
      String id = scopeIdsByName.get(scope.getName());
      provisionProtocolMappers(
          getRealmResource().clientScopes().get(id).getProtocolMappers(),
          scope.getProtocolMappers(),
          scope.getName(),
          changes);
    }
  }

  /**
   * Same rule as the client scope assignments: a {@code null} list means "not declared" and is left
   * alone, an empty one removes every mapper. Keycloak returns mappers in its own order and with
   * ids of its own, so they are matched by name.
   */
  private void provisionClientProtocolMappers(List<ClientRepresentation> clients, Changes changes) {
    for (ClientRepresentation client : clients) {
      if (client.getProtocolMappers() == null) {
        continue;
      }
      getClientByClientId(client.getClientId())
          .ifPresent(
              clientResource ->
                  provisionProtocolMappers(
                      clientResource.getProtocolMappers(),
                      client.getProtocolMappers(),
                      client.getClientId(),
                      changes));
    }
  }

  private void provisionProtocolMappers(
      ProtocolMappersResource mappersResource,
      List<ProtocolMapperRepresentation> desiredMappers,
      String parent,
      Changes changes) {
    List<ProtocolMapperRepresentation> existingMappers = mappersResource.getMappers();
    ProtocolMapperDiffer differ =
        new ProtocolMapperDiffer(
            existingMappers == null ? List.of() : existingMappers, desiredMappers);

    for (ProtocolMapperRepresentation mapper : differ.getElementsToDelete()) {
      changes.removed(Target.PROTOCOL_MAPPER, mapper.getName(), parent);
      mappersResource.delete(mapper.getId());
    }
    for (ProtocolMapperRepresentation mapper : differ.getElementsToAdd()) {
      changes.added(Target.PROTOCOL_MAPPER, mapper.getName(), parent);
      try (Response response = mappersResource.createMapper(mapper)) {
        assertResponseIs201Created(response);
      }
    }
    for (ToUpdate<ProtocolMapperRepresentation> update : differ.getElementsToUpdate()) {
      ProtocolMapperRepresentation mapper = update.newState();
      changes.updated(Target.PROTOCOL_MAPPER, mapper.getName(), parent, update.multiLineDiff());
      mappersResource.update(mapper.getId(), mapper);
    }
  }

  /**
   * Keycloak drops {@code defaultClientScopes} and {@code optionalClientScopes} on the client
   * update whatever they hold, so they are assigned through their own endpoints once the clients
   * are in place. Unlike an undeclared client field, which gets Keycloak's default, a {@code null}
   * list means "not declared" and is left alone, so assignments made outside this library survive.
   * An empty list removes every assignment.
   */
  private void provisionClientScopeAssignments(
      List<ClientRepresentation> clients, Changes changes) {
    boolean anyScopesConfigured =
        clients.stream()
            .anyMatch(
                client ->
                    client.getDefaultClientScopes() != null
                        || client.getOptionalClientScopes() != null);
    if (!anyScopesConfigured) {
      return;
    }

    Map<String, String> scopeIdsByName =
        getRealmResource().clientScopes().findAll().stream()
            .collect(
                Collectors.toMap(
                    ClientScopeRepresentation::getName, ClientScopeRepresentation::getId));

    for (ClientRepresentation client : clients) {
      getClientByClientId(client.getClientId())
          .ifPresent(
              clientResource ->
                  provisionClientScopes(clientResource, client, scopeIdsByName, changes));
    }
  }

  private void provisionClientScopes(
      ClientResource clientResource,
      ClientRepresentation desiredClient,
      Map<String, String> scopeIdsByName,
      Changes changes) {
    provisionClientScopes(
        desiredClient.getClientId(),
        desiredClient.getDefaultClientScopes(),
        scopeNames(clientResource.getDefaultClientScopes()),
        scopeIdsByName,
        Target.DEFAULT_CLIENT_SCOPE,
        clientResource::addDefaultClientScope,
        clientResource::removeDefaultClientScope,
        changes);
    provisionClientScopes(
        desiredClient.getClientId(),
        desiredClient.getOptionalClientScopes(),
        scopeNames(clientResource.getOptionalClientScopes()),
        scopeIdsByName,
        Target.OPTIONAL_CLIENT_SCOPE,
        clientResource::addOptionalClientScope,
        clientResource::removeOptionalClientScope,
        changes);
  }

  private void provisionClientScopes(
      String clientId,
      List<String> desiredScopeNames,
      List<String> assignedScopeNames,
      Map<String, String> scopeIdsByName,
      Target target,
      Consumer<String> assign,
      Consumer<String> unassign,
      Changes changes) {
    if (desiredScopeNames == null) {
      return;
    }

    AbstractDiffer<String> differ = new AbstractDiffer<>(assignedScopeNames, desiredScopeNames);
    for (String scopeName : differ.getElementsToDelete()) {
      changes.removed(target, scopeName, clientId);
      unassign.accept(resolveClientScopeId(scopeIdsByName, scopeName));
    }
    for (String scopeName : differ.getElementsToAdd()) {
      changes.added(target, scopeName, clientId);
      assign.accept(resolveClientScopeId(scopeIdsByName, scopeName));
    }
  }

  private static List<String> scopeNames(List<ClientScopeRepresentation> scopes) {
    return scopes.stream().map(ClientScopeRepresentation::getName).toList();
  }

  private static String resolveClientScopeId(Map<String, String> scopeIdsByName, String scopeName) {
    String scopeId = scopeIdsByName.get(scopeName);
    if (scopeId == null) {
      throw new KeycloakException(
          "Cannot assign client scope '" + scopeName + "': no such client scope in the realm");
    }
    return scopeId;
  }

  private void addClient(ClientRepresentation clientRepresentation, Changes changes) {
    changes.added(Target.CLIENT, clientRepresentation.getClientId());
    try (Response response = getRealmResource().clients().create(clientRepresentation)) {
      assertResponseIs201Created(response);
    }
  }

  private void deleteClient(ClientRepresentation clientRepresentation, Changes changes) {
    changes.removed(Target.CLIENT, clientRepresentation.getClientId());
    getRealmResource().clients().get(clientRepresentation.getId()).remove();
  }

  private void updateClient(ToUpdate<ClientRepresentation> update, Changes changes) {
    ClientRepresentation newClient = update.newState();
    changes.updated(Target.CLIENT, newClient.getClientId(), update.multiLineDiff());
    getRealmResource().clients().get(newClient.getId()).update(newClient);
  }

  private Changes changes() {
    return new Changes(log, realmName);
  }

  public RealmResource getRealmResource() {
    return keycloak.realm(this.realmName);
  }

  public UserResource getUserResource(String userId) {
    return getRealmResource().users().get(userId);
  }

  GroupResource getGroupResource(String id) {
    return getGroupsResource().group(id);
  }

  GroupsResource getGroupsResource() {
    return getRealmResource().groups();
  }

  public Optional<UserResource> getUserResourceByUsername(String username) {
    RealmResource realm = getRealmResource();
    return getUserByUsername(username).map(user -> realm.users().get(user.getId()));
  }

  public Optional<UserRepresentation> getUserByUsername(String username) {
    return searchUsersByExactUsername(username).collect(StreamUtil.toSingleOptionalElement());
  }

  public Stream<UserRepresentation> searchUsersByExactUsername(String username) {
    RealmResource realm = getRealmResource();
    return realm.users().searchByUsername(username, true).stream();
  }

  public List<UserRepresentation> getUsers(int maxResults) {
    // getRealm().users().list() only returns 100 users per default
    return getRealmResource().users().list(null, maxResults);
  }

  public List<RoleRepresentation> getRoles() {
    return getRealmResource().roles().list().stream()
        .sorted(Comparator.comparing(RoleRepresentation::getName))
        .toList();
  }

  public List<UserRepresentation> getUsersMarkedAsTemporaryAdmin() {
    return getRealmResource().users().searchByAttributes("is_temporary_admin:true");
  }

  public String createUser(UserRepresentation userRepresentation) {
    return createUser(getRealmResource().users(), userRepresentation);
  }

  public String createUser(UsersResource usersResource, UserRepresentation userRepresentation) {
    try (Response response = usersResource.create(userRepresentation)) {
      int status = response.getStatus();
      if (status == Response.Status.CONFLICT.getStatusCode()) {
        throw new KeycloakException(failureMessage(status) + " - User already exists");
      }
      if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
        throw new KeycloakException(failureMessage(status));
      }
      return CreatedResponseUtil.getCreatedId(response);
    }
  }

  public void deleteUser(String userId) {
    try (Response response = getRealmResource().users().delete(userId)) {
      assertResponseIs204NoContent(response);
    }
  }

  public void addRealmRoles(
      UserResource userResource, List<String> realmRoleNames, String username) {
    if (realmRoleNames.isEmpty()) {
      return;
    }
    RolesResource roles = getRealmResource().roles();
    List<RoleRepresentation> rolesToGrant =
        realmRoleNames.stream().map(name -> roles.get(name).toRepresentation()).toList();
    log.info("Adding realm roles {} to '{}'", realmRoleNames, username);
    userResource.roles().realmLevel().add(rolesToGrant);
  }

  public RoleScopeResource getServiceAccountRealmRoleScope(String clientId) {
    return getUserResource(
            getClientByClientId(clientId).orElseThrow().getServiceAccountUser().getId())
        .roles()
        .realmLevel();
  }

  public Optional<ClientResource> getClientByClientId(String clientId) {
    return getRealmResource().clients().findByClientId(clientId).stream()
        .collect(StreamUtil.toSingleOptionalElement())
        .map(ClientRepresentation::getId)
        .map(id -> getRealmResource().clients().get(id));
  }

  public void refreshToken() {
    keycloak.tokenManager().refreshToken();
  }

  /**
   * Provisions the realm's authentication flows to exactly match {@code flows}: adds missing ones,
   * updates existing ones, and deletes any flow no longer listed. {@code flows} is treated as the
   * full desired set, the same "declare it all" semantics as {@link
   * MasterRealmConfig#builtInClients()}. Keycloak's own flows are never touched, so only flows
   * created outside this call are at risk of deletion. Safe to call repeatedly.
   */
  public ProvisioningResult provisionFlows(List<AuthenticationFlowRepresentation> flows) {
    Changes changes = changes();
    List<AuthenticationFlowRepresentation> existingFlows =
        getRealmResource().flows().getFlows().stream()
            .filter(flow -> flow.isBuiltIn() != Boolean.TRUE)
            .toList();

    // Create first: renaming a flow would otherwise leave a window with neither.
    flows.forEach(flow -> createOrUpdateFlow(flow, existingFlows, changes));

    Set<String> configuredAliases =
        flows.stream().map(AuthenticationFlowRepresentation::getAlias).collect(Collectors.toSet());
    existingFlows.stream()
        .filter(flow -> !configuredAliases.contains(flow.getAlias()))
        .forEach(flow -> deleteFlow(flow, changes));
    return changes.result();
  }

  private void createOrUpdateFlow(
      AuthenticationFlowRepresentation flow,
      List<AuthenticationFlowRepresentation> existingFlows,
      Changes changes) {
    Optional<AuthenticationFlowRepresentation> existingFlow =
        existingFlows.stream().filter(f -> Objects.equals(f.getAlias(), flow.getAlias())).findAny();
    if (existingFlow.isEmpty()) {
      changes.added(Target.FLOW, flow.getAlias());
      try (Response response = getRealmResource().flows().createFlow(flow)) {
        assertResponseIs201Created(response);
      }
    } else {
      updateFlowIfChanged(existingFlow.get(), flow, changes);
    }
  }

  private void updateFlowIfChanged(
      AuthenticationFlowRepresentation existingFlow,
      AuthenticationFlowRepresentation flow,
      Changes changes) {
    String description = Declared.orUnset(flow.getDescription(), existingFlow.getDescription());
    if (Objects.equals(description, existingFlow.getDescription())
        && Objects.equals(flow.getProviderId(), existingFlow.getProviderId())
        && Objects.equals(flow.isTopLevel(), existingFlow.isTopLevel())) {
      return;
    }

    AuthenticationFlowRepresentation update = new AuthenticationFlowRepresentation();
    update.setAlias(flow.getAlias());
    update.setDescription(description);
    update.setProviderId(flow.getProviderId());
    update.setTopLevel(flow.isTopLevel());
    update.setBuiltIn(flow.isBuiltIn());
    changes.updated(
        Target.FLOW,
        flow.getAlias(),
        Differ.calculateMultilineDiff(
            AbstractDiffer.toJson(flowFields(existingFlow)), AbstractDiffer.toJson(update)));
    getRealmResource().flows().updateFlow(existingFlow.getId(), update);
  }

  /** Without the id and the executions, which a flow's update does not carry. */
  private static AuthenticationFlowRepresentation flowFields(
      AuthenticationFlowRepresentation flow) {
    AuthenticationFlowRepresentation fields = new AuthenticationFlowRepresentation();
    fields.setAlias(flow.getAlias());
    fields.setDescription(flow.getDescription());
    fields.setProviderId(flow.getProviderId());
    fields.setTopLevel(flow.isTopLevel());
    fields.setBuiltIn(flow.isBuiltIn());
    return fields;
  }

  private void deleteFlow(AuthenticationFlowRepresentation flow, Changes changes) {
    changes.removed(Target.FLOW, flow.getAlias());
    getRealmResource().flows().deleteFlow(flow.getId());
  }

  /**
   * Each entry of {@code configuredExecutions} is the full desired content of that flow, so an
   * execution missing from it is removed. {@code parentFlow} is only the flow the sub-flows belong
   * to; pass it as a key as well to configure its own executions.
   *
   * <p>Entries are applied in iteration order, and a sub-flow only exists once the entry declaring
   * it has run, so pass an ordered map: with {@link Map#of} the order is unspecified and an entry
   * naming a sub-flow can run first and fail with a 404.
   */
  public ProvisioningResult provisionExecutions(
      Map<String, List<FlowExecution>> configuredExecutions) {
    Changes changes = changes();
    configuredExecutions.forEach(
        (flowName, flowExecutions) -> {
          List<AuthenticationExecutionInfoRepresentation> executions =
              toRepresentations(flowExecutions);
          assertExecutionsAreDistinguishable(flowName, executions);

          // Re-read per flow: an earlier flow's changes are already applied, and getExecutions
          // returns the whole tree flattened, so it has to be narrowed to this flow's own level.
          List<AuthenticationExecutionInfoRepresentation> existingExecutions =
              directExecutionsOf(flowName);

          AuthenticationExecutionRepresentationDiffer differ =
              getExecutionDiffer(existingExecutions, executions);
          differ.getElementsToDelete().forEach(exe -> deleteExecution(exe, flowName, changes));
          differ.getElementsToAdd().forEach(exe -> addExecution(exe, flowName, changes));
          differ.getElementsToUpdate().forEach(exe -> updateExecution(exe, flowName, changes));
        });
    return changes.result();
  }

  /**
   * Keycloak allows the same authenticator twice in one flow, but this library cannot tell the two
   * apart across runs: they share an identity, and the only thing separating them is their
   * position, which is deliberately excluded so that a reorder is not a delete plus an add.
   */
  private static void assertExecutionsAreDistinguishable(
      String flowName, List<AuthenticationExecutionInfoRepresentation> executions) {
    Set<String> seen = new LinkedHashSet<>();
    for (AuthenticationExecutionInfoRepresentation execution : executions) {
      String id = AuthenticationExecutionRepresentationDiffer.id(execution);
      Validate.isTrue(
          seen.add(id),
          "Flow '%s' declares '%s' more than once, which cannot be provisioned",
          flowName,
          id);
    }
  }

  /** Keycloak orders executions by priority and derives the index from it on read. */
  private static List<AuthenticationExecutionInfoRepresentation> toRepresentations(
      List<FlowExecution> flowExecutions) {
    List<AuthenticationExecutionInfoRepresentation> representations = new ArrayList<>();
    for (int priority = 0; priority < flowExecutions.size(); priority++) {
      representations.add(toRepresentation(flowExecutions.get(priority), priority));
    }
    return representations;
  }

  private static AuthenticationExecutionInfoRepresentation toRepresentation(
      FlowExecution flowExecution, int priority) {
    AuthenticationExecutionInfoRepresentation representation =
        new AuthenticationExecutionInfoRepresentation();
    representation.setRequirement(flowExecution.requirement().name());
    representation.setPriority(priority);
    switch (flowExecution) {
      case FlowExecution.Authenticator authenticator ->
          representation.setProviderId(authenticator.providerId());
      case FlowExecution.SubFlow subFlow -> {
        representation.setAuthenticationFlow(Boolean.TRUE);
        // alias creates the nested flow; displayName is how Keycloak reports it back, so setting
        // both makes the desired and the existing side comparable without a fallback.
        representation.setAlias(subFlow.alias());
        representation.setDisplayName(subFlow.alias());
        representation.setDescription(subFlow.description());
      }
    }
    return representation;
  }

  /** Executions directly on {@code flowAlias}, without those of its sub-flows. */
  private List<AuthenticationExecutionInfoRepresentation> directExecutionsOf(String flowAlias) {
    return getRealmResource().flows().getExecutions(flowAlias).stream()
        .filter(execution -> execution.getLevel() == 0)
        .toList();
  }

  private static AuthenticationExecutionRepresentationDiffer getExecutionDiffer(
      List<AuthenticationExecutionInfoRepresentation> existingExecutions,
      List<AuthenticationExecutionInfoRepresentation> configuredExecutions) {
    return new AuthenticationExecutionRepresentationDiffer(
        existingExecutions, configuredExecutions);
  }

  private void deleteExecution(
      AuthenticationExecutionInfoRepresentation execution, String parentFlow, Changes changes) {
    changes.removed(
        Target.EXECUTION, AuthenticationExecutionRepresentationDiffer.id(execution), parentFlow);
    getRealmResource().flows().removeExecution(execution.getId());
  }

  private void addExecution(
      AuthenticationExecutionInfoRepresentation execution, String parentFlow, Changes changes) {
    changes.added(
        Target.EXECUTION, AuthenticationExecutionRepresentationDiffer.id(execution), parentFlow);
    if (execution.getAuthenticationFlow() == Boolean.TRUE) {
      getRealmResource()
          .flows()
          .addExecutionFlow(
              parentFlow,
              Map.of(
                  "alias",
                  execution.getAlias(),
                  "description",
                  execution.getDescription() == null ? "" : execution.getDescription(),
                  "type",
                  "basic-flow"));
    } else {
      getRealmResource()
          .flows()
          .addExecution(parentFlow, Map.of("provider", execution.getProviderId()));
    }
    AuthenticationExecutionInfoRepresentation addedExecution =
        getAddedExecution(execution, parentFlow);
    addedExecution.setRequirement(execution.getRequirement());
    addedExecution.setPriority(execution.getPriority());
    getRealmResource().flows().updateExecutions(parentFlow, addedExecution);
  }

  /** Keycloak appends, so with several executions of one provider the new one is the last match. */
  private AuthenticationExecutionInfoRepresentation getAddedExecution(
      AuthenticationExecutionInfoRepresentation execution, String parentFlow) {
    return directExecutionsOf(parentFlow).stream()
        .filter(
            exec ->
                AuthenticationExecutionRepresentationDiffer.id(exec)
                    .equals(AuthenticationExecutionRepresentationDiffer.id(execution)))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private void updateExecution(
      ToUpdate<AuthenticationExecutionInfoRepresentation> toUpdate,
      String parentFlow,
      Changes changes) {
    AuthenticationExecutionInfoRepresentation executionUpdate = toUpdate.newState();
    changes.updated(
        Target.EXECUTION,
        AuthenticationExecutionRepresentationDiffer.id(executionUpdate),
        parentFlow,
        toUpdate.multiLineDiff());
    getRealmResource().flows().updateExecutions(parentFlow, executionUpdate);
  }

  public ProvisioningResult provisionUserProfile(UserProfileConfig userProfileConfig) {
    Changes changes = changes();
    UserProfileResource userProfileResource = getRealmResource().users().userProfile();
    UPConfig upConfig = userProfileResource.getConfiguration();
    String before = AbstractDiffer.toJson(upConfig);

    ensureGroupsExist(upConfig, userProfileConfig.attributes());

    List<UPAttribute> desiredAttributes =
        userProfileConfig.attributes().stream().map(RealmManager::buildAttribute).toList();

    UserProfileAttributeDiffer differ =
        new UserProfileAttributeDiffer(upConfig.getAttributes(), desiredAttributes);

    // getElementsToUpdate() already mutated upConfig's own attribute elements in place; only
    // add/delete require explicit list surgery here.
    List<UPAttribute> resultingAttributes = new ArrayList<>(upConfig.getAttributes());
    resultingAttributes.removeAll(differ.getElementsToDelete());
    resultingAttributes.addAll(differ.getElementsToAdd());
    upConfig.setAttributes(resultingAttributes);

    userProfileConfig.userProfileCustomizer().accept(upConfig);
    String after = AbstractDiffer.toJson(upConfig);
    if (!before.equals(after)) {
      changes.updated(Target.USER_PROFILE, realmName, Differ.calculateMultilineDiff(before, after));
      userProfileResource.update(upConfig);
    }
    return changes.result();
  }

  private static void ensureGroupsExist(
      UPConfig upConfig, List<UserProfileConfig.AttributeConfig> attributeConfigs) {
    Set<String> existingGroupNames =
        upConfig.getGroups().stream().map(UPGroup::getName).collect(Collectors.toSet());
    attributeConfigs.stream()
        .map(UserProfileConfig.AttributeConfig::group)
        .filter(Objects::nonNull)
        .distinct()
        .filter(groupName -> !existingGroupNames.contains(groupName))
        .forEach(groupName -> upConfig.addGroup(new UPGroup(groupName)));
  }

  private static UPAttribute buildAttribute(UserProfileConfig.AttributeConfig attributeConfig) {
    UPAttribute attribute = new UPAttribute(attributeConfig.attributeName());
    attribute.setDisplayName(attributeConfig.displayName());
    attribute.setGroup(attributeConfig.group());

    Set<UserProfileConfig.UserProfileRole> requiredFor = attributeConfig.requiredFor();
    if (requiredFor != null) {
      UPAttributeRequired required = new UPAttributeRequired();
      required.setRoles(toKeycloakRoleNames(requiredFor));
      required.setScopes(Set.of());
      attribute.setRequired(required);
    }

    UPAttributePermissions permissions = new UPAttributePermissions();
    permissions.setView(toKeycloakRoleNames(attributeConfig.viewableBy()));
    permissions.setEdit(toKeycloakRoleNames(attributeConfig.editableBy()));
    attribute.setPermissions(permissions);

    attribute.setValidations(toValidations(attributeConfig.validationRules()));

    return attribute;
  }

  private static Map<String, Map<String, Object>> toValidations(
      List<UserProfileConfig.ValidationRule> validationRules) {
    Map<String, Map<String, Object>> validations = new LinkedHashMap<>();
    for (UserProfileConfig.ValidationRule rule : validationRules) {
      validations.put(rule.ruleId(), rule.toMap());
    }
    return validations;
  }

  private static Set<String> toKeycloakRoleNames(Set<UserProfileConfig.UserProfileRole> roles) {
    return roles.stream()
        .map(UserProfileConfig.UserProfileRole::keycloakRoleName)
        .collect(Collectors.toSet());
  }

  public AuthenticationManagementManager getAuthenticationManagementManager() {
    return new AuthenticationManagementManager(getRealmResource());
  }
}
