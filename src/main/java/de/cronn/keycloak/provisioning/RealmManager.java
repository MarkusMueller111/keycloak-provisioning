package de.cronn.keycloak.provisioning;

import de.cronn.commons.lang.StreamUtil;
import de.cronn.keycloak.provisioning.connection.KeycloakConnectionManager;
import de.cronn.keycloak.provisioning.differ.AbstractDiffer;
import de.cronn.keycloak.provisioning.differ.AuthenticationExecutionRepresentationDiffer;
import de.cronn.keycloak.provisioning.differ.ClientRepresentationDiffer;
import de.cronn.keycloak.provisioning.differ.Differ;
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
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.GroupsResource;
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
  private final SystemClientNaming clientNaming;

  /**
   * Usually obtained via {@link KeycloakConnectionManager#realmManager(String)}, which supplies the
   * naming it was configured with; construct one directly only when managing the {@link Keycloak}
   * connection yourself.
   */
  public RealmManager(Keycloak keycloak, String realmName, SystemClientNaming clientNaming) {
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
   * Reconciles the realm roles composed into the realm's default role (i.e. granted to every user)
   * to exactly match {@code roleNames}: adds missing ones, removes any composite realm role not
   * listed. {@code roleNames} is treated as the full desired set, the same "declare it all"
   * semantics as {@link MasterRealmConfig#builtInClients()}. Safe to call repeatedly.
   */
  public void reconcileDefaultRoleComposites(Collection<String> roleNames) {
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
      log.info(
          "Adding default role composites: {}",
          toAdd.stream().map(RoleRepresentation::getName).toList());
      defaultRoleResource.addComposites(toAdd);
    }

    List<RoleRepresentation> toRemove =
        existingComposites.stream().filter(role -> !roleNames.contains(role.getName())).toList();
    if (!toRemove.isEmpty()) {
      log.info(
          "Removing default role composites no longer configured: {}",
          toRemove.stream().map(RoleRepresentation::getName).toList());
      defaultRoleResource.deleteComposites(toRemove);
    }
  }

  /**
   * Reconciles Keycloak's own clients: what {@code builtInClients} declares is applied, every other
   * built-in is reset to enabled. Removing an entry therefore undoes it. Clients this library or a
   * consumer provisioned are out of scope, those belong to {@link #createOrUpdateClients(List)}.
   */
  public void reconcileBuiltInClients(Collection<BuiltInClientConfig> builtInClients) {
    Map<KeycloakProvidedClient, BuiltInClientConfig> byClient = new LinkedHashMap<>();
    for (BuiltInClientConfig config : builtInClients) {
      // Before the first write: two entries for one client contradict each other, and reporting
      // that half-way through leaves the realm partly reconciled.
      Validate.isTrue(
          byClient.put(config.client(), config) == null,
          "Client '%s' is configured more than once",
          config.client().getClientId());
    }

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

      log.info(
          "Built-in client '{}' requires an update:\n{}",
          builtIn.getClientId(),
          Differ.calculateMultilineDiff(before, after));
      clientsResource.get(client.getId()).update(client);
    }
  }

  public void createOrUpdateClients(List<ClientRepresentation> clients) {
    clients.forEach(this::assertClientIsManaged);
    Predicate<ClientRepresentation> existingClientsPredicate =
        c -> clientNaming.isManaged(c.getClientId());
    createOrUpdateClients(clients, existingClientsPredicate);
  }

  /**
   * Reconciles one client outside the managed namespace, scoped to its own id so nothing else can
   * be deleted. For clients this library creates but does not own the lifecycle of. E.g. the
   * provisioning client itself, which must stay clear of {@link #createOrUpdateClients} so a
   * consumer's own reconciliation does not delete the identity it is connected as.
   */
  public void createOrUpdateUnmanagedClient(ClientRepresentation client) {
    String clientId = client.getClientId();
    Validate.isTrue(
        !clientNaming.isManaged(clientId),
        "Client '%s' carries the managed prefix '%s' and belongs in createOrUpdateClients",
        clientId,
        clientNaming.prefix());
    createOrUpdateClients(List.of(client), existing -> clientId.equals(existing.getClientId()));
  }

  /**
   * Without the prefix a client would be created but never recognized as existing again, so the
   * next run would try to create it a second time and fail with a conflict.
   */
  private void assertClientIsManaged(ClientRepresentation client) {
    String clientId = client.getClientId();
    if (!clientNaming.isManaged(clientId)) {
      throw new KeycloakException(
          "Client '"
              + clientId
              + "' cannot be provisioned: its id must carry the configured prefix '"
              + clientNaming.prefix()
              + "'. Use SystemClientNaming.clientId(...) to build it, or configure a naming whose"
              + " prefix matches.");
    }
  }

  void createOrUpdateClients(
      List<ClientRepresentation> clients,
      Predicate<ClientRepresentation> existingClientsToConsiderPredicate) {
    List<ClientRepresentation> existingClients =
        getRealmResource().clients().findAll().stream()
            .filter(existingClientsToConsiderPredicate)
            .toList();

    ClientRepresentationDiffer clientDiffer = getClientsDiffer(existingClients, clients);
    clientDiffer.getElementsToAdd().forEach(this::addClient);
    clientDiffer.getElementsToDelete().forEach(this::deleteClient);
    clientDiffer.getElementsToUpdate().forEach(this::updateClient);

    reconcileClientScopeAssignments(clients);
  }

  /**
   * Keycloak ignores {@code defaultClientScopes} and {@code optionalClientScopes} when a client is
   * updated, so they are assigned through their own endpoints once the clients themselves are in
   * place. A {@code null} list means "leave as is"; an empty one removes every assignment.
   */
  private void reconcileClientScopeAssignments(List<ClientRepresentation> clients) {
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
              clientResource -> reconcileClientScopes(clientResource, client, scopeIdsByName));
    }
  }

  private void reconcileClientScopes(
      ClientResource clientResource,
      ClientRepresentation desiredClient,
      Map<String, String> scopeIdsByName) {
    reconcileClientScopes(
        desiredClient.getClientId(),
        desiredClient.getDefaultClientScopes(),
        scopeNames(clientResource.getDefaultClientScopes()),
        scopeIdsByName,
        "default",
        clientResource::addDefaultClientScope,
        clientResource::removeDefaultClientScope);
    reconcileClientScopes(
        desiredClient.getClientId(),
        desiredClient.getOptionalClientScopes(),
        scopeNames(clientResource.getOptionalClientScopes()),
        scopeIdsByName,
        "optional",
        clientResource::addOptionalClientScope,
        clientResource::removeOptionalClientScope);
  }

  private void reconcileClientScopes(
      String clientId,
      List<String> desiredScopeNames,
      List<String> assignedScopeNames,
      Map<String, String> scopeIdsByName,
      String kind,
      Consumer<String> assign,
      Consumer<String> unassign) {
    if (desiredScopeNames == null) {
      return;
    }

    AbstractDiffer<String> differ = new AbstractDiffer<>(assignedScopeNames, desiredScopeNames);
    for (String scopeName : differ.getElementsToDelete()) {
      log.info("Removing {} client scope '{}' from client '{}'", kind, scopeName, clientId);
      unassign.accept(resolveClientScopeId(scopeIdsByName, scopeName));
    }
    for (String scopeName : differ.getElementsToAdd()) {
      log.info("Adding {} client scope '{}' to client '{}'", kind, scopeName, clientId);
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

  void addClient(ClientRepresentation clientRepresentation) {
    log.info("Adding client '{}'", clientRepresentation.getClientId());
    try (Response response = getRealmResource().clients().create(clientRepresentation)) {
      assertResponseIs201Created(response);
    }
  }

  void deleteClient(ClientRepresentation clientRepresentation) {
    log.info(
        "Removing client '{}' from Keycloak realm '{}'",
        clientRepresentation.getClientId(),
        realmName);
    getRealmResource().clients().get(clientRepresentation.getId()).remove();
  }

  void updateClient(ToUpdate<ClientRepresentation> update) {
    ClientRepresentation newClient = update.newState();
    log.info(
        "Client '{}' already exists, but update is required:\n{}",
        newClient.getClientId(),
        update.multiLineDiff());
    getRealmResource().clients().get(newClient.getId()).update(newClient);
    log.info("Client '{}' updated.", newClient.getClientId());
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
    log.info("Deleting user with id '{}'", userId);
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
   * Reconciles the realm's authentication flows to exactly match {@code flows}: adds missing ones,
   * updates existing ones, and deletes any flow no longer listed. {@code flows} is treated as the
   * full desired set, the same "declare it all" semantics as {@link
   * MasterRealmConfig#builtInClients()}. Keycloak's own flows are never touched, so only flows
   * created outside this call are at risk of deletion. Safe to call repeatedly.
   */
  public void configureFlows(List<AuthenticationFlowRepresentation> flows) {
    List<AuthenticationFlowRepresentation> existingFlows =
        getRealmResource().flows().getFlows().stream()
            .filter(flow -> flow.isBuiltIn() != Boolean.TRUE)
            .toList();

    Set<String> configuredAliases =
        flows.stream().map(AuthenticationFlowRepresentation::getAlias).collect(Collectors.toSet());
    existingFlows.stream()
        .filter(flow -> !configuredAliases.contains(flow.getAlias()))
        .forEach(this::deleteFlow);

    flows.forEach(flow -> configureFlow(flow, existingFlows));
  }

  private void configureFlow(
      AuthenticationFlowRepresentation flow, List<AuthenticationFlowRepresentation> existingFlows) {
    Optional<AuthenticationFlowRepresentation> existingFlow =
        existingFlows.stream().filter(f -> Objects.equals(f.getAlias(), flow.getAlias())).findAny();
    if (existingFlow.isEmpty()) {
      log.info(
          "Adding authentication flow '{}' to Keycloak realm '{}'", flow.getAlias(), realmName);
      try (Response response = getRealmResource().flows().createFlow(flow)) {
        assertResponseIs201Created(response);
      }
    } else {
      log.info(
          "Configuring authentication flow '{}' on Keycloak realm '{}'",
          flow.getAlias(),
          realmName);
      getRealmResource().flows().updateFlow(existingFlow.get().getId(), flow);
    }
  }

  private void deleteFlow(AuthenticationFlowRepresentation flow) {
    log.info(
        "Removing authentication flow '{}' from Keycloak realm '{}'", flow.getAlias(), realmName);
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
  public void addOrUpdateExecutions(Map<String, List<FlowExecution>> configuredExecutions) {
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
          differ.getElementsToDelete().forEach(exe -> deleteExecution(exe, flowName));
          differ.getElementsToAdd().forEach(exe -> addExecution(exe, flowName));
          differ.getElementsToUpdate().forEach(exe -> updateExecution(exe, flowName));
        });
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
          "Flow '%s' declares '%s' more than once, which cannot be reconciled",
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
      AuthenticationExecutionInfoRepresentation execution, String parentFlow) {
    log.info(
        "Removing authentication execution '{}' from parent flow '{}' in Keycloak realm '{}'",
        execution.getProviderId(),
        parentFlow,
        realmName);
    getRealmResource().flows().removeExecution(execution.getId());
  }

  private void addExecution(
      AuthenticationExecutionInfoRepresentation execution, String parentFlow) {
    log.info(
        "Adding authentication execution '{}' to parent flow {} to Keycloak realm '{}'",
        execution.getProviderId(),
        parentFlow,
        realmName);
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
      ToUpdate<AuthenticationExecutionInfoRepresentation> toUpdate, String parentFlow) {
    AuthenticationExecutionInfoRepresentation executionUpdate = toUpdate.newState();
    log.info(
        "Updating authentication execution '{}' of parent flow {} on Keycloak realm '{}'",
        executionUpdate.getProviderId(),
        parentFlow,
        realmName);
    getRealmResource().flows().updateExecutions(parentFlow, executionUpdate);
  }

  public void configureUserProfile(UserProfileConfig userProfileConfig) {
    UserProfileResource userProfileResource = getRealmResource().users().userProfile();
    UPConfig upConfig = userProfileResource.getConfiguration();

    ensureGroupsExist(upConfig, userProfileConfig.attributes());

    List<UPAttribute> desiredAttributes =
        userProfileConfig.attributes().stream().map(RealmManager::buildAttribute).toList();

    UserProfileAttributeDiffer differ =
        new UserProfileAttributeDiffer(upConfig.getAttributes(), desiredAttributes);

    differ
        .getElementsToDelete()
        .forEach(a -> log.info("Removing user profile attribute '{}'", a.getName()));
    differ
        .getElementsToAdd()
        .forEach(a -> log.info("Adding user profile attribute '{}'", a.getName()));
    for (ToUpdate<UPAttribute> update : differ.getElementsToUpdate()) {
      log.info(
          "User profile attribute '{}' updated:\n{}",
          update.newState().getName(),
          update.multiLineDiff());
    }

    // getElementsToUpdate() already mutated upConfig's own attribute elements in place; only
    // add/delete require explicit list surgery here.
    List<UPAttribute> resultingAttributes = new ArrayList<>(upConfig.getAttributes());
    resultingAttributes.removeAll(differ.getElementsToDelete());
    resultingAttributes.addAll(differ.getElementsToAdd());
    upConfig.setAttributes(resultingAttributes);

    userProfileConfig.userProfileCustomizer().accept(upConfig);
    userProfileResource.update(upConfig);
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
        .forEach(
            groupName -> {
              log.info("Adding user profile group '{}'", groupName);
              upConfig.addGroup(new UPGroup(groupName));
            });
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
