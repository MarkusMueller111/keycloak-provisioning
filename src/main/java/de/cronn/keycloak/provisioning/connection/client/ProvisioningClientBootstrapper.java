package de.cronn.keycloak.provisioning.connection.client;

import de.cronn.keycloak.provisioning.RealmManager;
import de.cronn.keycloak.provisioning.master.MasterRealmProvisioner;
import java.util.List;
import java.util.Map;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional helper to create the client this library itself uses to authenticate against Keycloak,
 * for first-start setups where that client does not exist yet. Not called automatically by {@link
 * MasterRealmProvisioner} — invoke it explicitly (e.g. with an admin client authenticated via the
 * bootstrap admin user) before creating the {@code Keycloak} connection you hand to {@link
 * MasterRealmProvisioner}.
 */
public final class ProvisioningClientBootstrapper {

  private static final Logger log = LoggerFactory.getLogger(ProvisioningClientBootstrapper.class);

  private ProvisioningClientBootstrapper() {}

  /**
   * Creates (or updates) a confidential client with a service account in the master realm and
   * grants that service account the realm's {@code admin} role so it can be used to authenticate
   * future provisioning runs.
   */
  public static void registerClient(
      RealmManager realmManager, KeycloakProvisioningClientConfig provisioningClientConfig) {
    ClientRepresentation client = getProvisioningClientRepresentation(provisioningClientConfig);
    realmManager.createOrUpdateClients(List.of(client));
    RoleRepresentation adminRole =
        realmManager
            .getRealmResource()
            .roles()
            .get(RealmManager.ADMIN_REALM_ROLE)
            .toRepresentation();
    realmManager.getServiceAccountRealmRoleScope(client.getClientId()).add(List.of(adminRole));

    log.info("Added admin role to service account for client '{}'", client.getClientId());
  }

  private static ClientRepresentation getProvisioningClientRepresentation(
      KeycloakProvisioningClientConfig provisioningClientConfig) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(provisioningClientConfig.clientId().value());
    client.setSecret(provisioningClientConfig.secret());
    client.setAuthorizationServicesEnabled(false);
    client.setServiceAccountsEnabled(true);
    client.setPublicClient(false);
    client.setStandardFlowEnabled(false);
    client.setEnabled(true);
    client.setName(provisioningClientConfig.name());
    client.setDescription(provisioningClientConfig.description());
    client.setAttributes(Map.of("realm_client", "false"));
    // A service account that never issues user tokens has no use for the realm's default scopes.
    client.setDefaultClientScopes(List.of());
    client.setOptionalClientScopes(List.of());

    return client;
  }
}
