package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.ClientRepresentation;

public class ClientRepresentationDiffer extends AbstractDiffer<ClientRepresentation> {

  private static PropertyUpdater<ClientRepresentation> clientRepresentationUpdater() {
    return (target, source) -> {
      target.setClientId(source.getClientId());
      target.setName(source.getName());
      target.setDescription(source.getDescription());
      target.setPublicClient(source.isPublicClient());
      target.setRootUrl(source.getRootUrl());
      target.setBaseUrl(source.getBaseUrl());
      target.setAdminUrl(source.getAdminUrl());
      target.setDirectAccessGrantsEnabled(source.isDirectAccessGrantsEnabled());
      target.setStandardFlowEnabled(source.isStandardFlowEnabled());
      target.setServiceAccountsEnabled(source.isServiceAccountsEnabled());
      target.setAuthorizationServicesEnabled(source.getAuthorizationServicesEnabled());
      target.setProtocol(source.getProtocol());
      target.setFrontchannelLogout(source.isFrontchannelLogout());
      target.setRedirectUris(source.getRedirectUris());
      target.setWebOrigins(source.getWebOrigins());
      if (source.getSecret() != null) {
        // A null secret means "not declared", not "remove it".
        target.setSecret(source.getSecret());
      }
      target.setAttributes(source.getAttributes());
      target.setEnabled(source.isEnabled());
      // Scope assignments are left out on purpose: Keycloak ignores them on the client update,
      // so RealmManager provisions them through the dedicated endpoints afterwards.
    };
  }

  public ClientRepresentationDiffer(
      List<ClientRepresentation> target, List<ClientRepresentation> source) {
    super(target, source, clientRepresentationUpdater(), ClientRepresentation::getClientId);
  }
}
