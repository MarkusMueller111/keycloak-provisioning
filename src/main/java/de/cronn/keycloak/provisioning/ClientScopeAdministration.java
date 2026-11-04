package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.ManagedNaming.ManagedEntity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, changes and deletes client scopes on demand, e.g. while the application runs. Only
 * scopes outside the managed namespace can be written, and none of Keycloak's own.
 *
 * <p>A scope is validated and written exactly as {@link
 * RealmManager#provisionManagedClientScopes(List)} writes one, including its protocol mappers.
 * Every write is strict: creating a scope that exists, or changing one that does not, fails rather
 * than falling back to the other.
 */
public final class ClientScopeAdministration {

  private static final Logger log = LoggerFactory.getLogger(ClientScopeAdministration.class);

  private final RealmManager realmManager;

  public ClientScopeAdministration(RealmManager realmManager) {
    this.realmManager = Objects.requireNonNull(realmManager);
  }

  /** Returns the new scope's id. */
  public String createClientScope(ClientScopeRepresentation clientScope) {
    requireWritable(clientScope.getName());
    if (find(clientScope.getName()).isPresent()) {
      throw new KeycloakException("Client scope '" + clientScope.getName() + "' already exists");
    }
    return realmManager.createUnmanagedClientScope(clientScope);
  }

  /** Matched by name, with the same full-desired-state semantics as provisioning. */
  public void updateClientScope(ClientScopeRepresentation clientScope) {
    getWritable(clientScope.getName());
    realmManager.createOrUpdateUnmanagedClientScope(clientScope);
  }

  /** Keycloak also removes the scope from every client it is assigned to. */
  public void deleteClientScope(String name) {
    ClientScopeRepresentation existing = getWritable(name);
    new Changes(log, realmManager.getRealmName()).removed(Target.CLIENT_SCOPE, name);
    realmManager.getRealmResource().clientScopes().get(existing.getId()).remove();
  }

  private ClientScopeRepresentation getWritable(String name) {
    requireWritable(name);
    return find(name).orElseThrow(() -> new KeycloakException("No client scope '" + name + "'"));
  }

  private void requireWritable(String name) {
    Validate.notBlank(name, "client scope name must not be blank");
    ManagedNaming naming = realmManager.naming();
    if (naming.isManaged(ManagedEntity.CLIENT_SCOPE, name)) {
      throw new KeycloakException(
          "Client scope '"
              + name
              + "' carries the managed prefix '"
              + naming.prefix(ManagedEntity.CLIENT_SCOPE)
              + "' and is written by provisioning only");
    }
    if (KeycloakProvidedClientScope.isKeycloakProvided(name)) {
      throw new KeycloakException(
          "Client scope '" + name + "' is one of Keycloak's own and cannot be changed on demand");
    }
  }

  private Optional<ClientScopeRepresentation> find(String name) {
    return realmManager.getRealmResource().clientScopes().findAll().stream()
        .filter(scope -> name.equals(scope.getName()))
        .findFirst();
  }
}
