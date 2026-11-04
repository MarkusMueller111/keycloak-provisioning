package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.ManagedNaming.ManagedEntity;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.idm.ClientRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, changes and deletes clients on demand, e.g. while the application runs. Only clients
 * outside the managed namespace can be written, and none of Keycloak's own or the provisioning
 * client itself.
 *
 * <p>A client is validated and written exactly as {@link
 * RealmManager#provisionManagedClients(List)} writes one, including its scope assignments and
 * protocol mappers. Every write is strict: creating a client that exists, or changing one that does
 * not, fails rather than falling back to the other.
 */
public final class ClientAdministration {

  private static final Logger log = LoggerFactory.getLogger(ClientAdministration.class);

  /** Keycloak marks its per-realm admin clients, e.g. {@code <realm>-realm} in master. */
  private static final String REALM_CLIENT_ATTRIBUTE = "realm_client";

  private final RealmManager realmManager;

  public ClientAdministration(RealmManager realmManager) {
    this.realmManager = Objects.requireNonNull(realmManager);
  }

  /** Returns the new client's id, Keycloak's internal one rather than its {@code clientId}. */
  public String createClient(ClientRepresentation client) {
    requireWritable(client.getClientId());
    if (find(client.getClientId()).isPresent()) {
      throw new KeycloakException("Client '" + client.getClientId() + "' already exists");
    }
    return realmManager.createUnmanagedClient(client);
  }

  /** Matched by {@code clientId}, with the same full-desired-state semantics as provisioning. */
  public void updateClient(ClientRepresentation client) {
    getWritable(client.getClientId());
    realmManager.createOrUpdateUnmanagedClient(client);
  }

  /** Any client, managed or not, Keycloak's own included. */
  public Optional<ClientRepresentation> findClientByClientId(String clientId) {
    Validate.notBlank(clientId, "clientId must not be blank");
    return find(clientId);
  }

  public void deleteClient(String clientId) {
    ClientRepresentation existing = getWritable(clientId);
    new Changes(log, realmManager.getRealmName()).removed(Target.CLIENT, clientId);
    realmManager.deleteClient(existing);
  }

  private ClientRepresentation getWritable(String clientId) {
    requireWritable(clientId);
    ClientRepresentation existing =
        find(clientId).orElseThrow(() -> new KeycloakException("No client '" + clientId + "'"));
    if (existing.getAttributes() != null
        && "true".equals(existing.getAttributes().get(REALM_CLIENT_ATTRIBUTE))) {
      throw keycloakProvided(clientId);
    }
    return existing;
  }

  private void requireWritable(String clientId) {
    Validate.notBlank(clientId, "clientId must not be blank");
    ManagedNaming naming = realmManager.naming();
    if (naming.isManaged(ManagedEntity.CLIENT, clientId)) {
      throw new KeycloakException(
          "Client '"
              + clientId
              + "' carries the managed prefix '"
              + naming.prefix(ManagedEntity.CLIENT)
              + "' and is written by provisioning only");
    }
    if (naming.isProvisioningClient(clientId)) {
      throw new KeycloakException(
          "Client '" + clientId + "' is the provisioning client and cannot be changed on demand");
    }
    if (Arrays.stream(KeycloakProvidedClient.values())
        .anyMatch(builtIn -> builtIn.getClientId().equals(clientId))) {
      throw keycloakProvided(clientId);
    }
  }

  private static KeycloakException keycloakProvided(String clientId) {
    return new KeycloakException(
        "Client '"
            + clientId
            + "' is one of Keycloak's own; configure it with provisionBuiltInClients instead");
  }

  private Optional<ClientRepresentation> find(String clientId) {
    return realmManager.getRealmResource().clients().findByClientId(clientId).stream().findFirst();
  }
}
