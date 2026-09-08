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
      target.setFrontchannelLogout(source.isFrontchannelLogout());
      target.setRedirectUris(source.getRedirectUris());
      target.setWebOrigins(source.getWebOrigins());
      target.setSecret(source.getSecret());
      target.setAttributes(source.getAttributes());
      // Scope assignments are left out on purpose: Keycloak ignores them on the client update,
      // so RealmManager reconciles them through the dedicated endpoints afterwards.
    };
  }

  public ClientRepresentationDiffer(
      List<ClientRepresentation> target, List<ClientRepresentation> source) {
    super(target, source, clientRepresentationUpdater(), ClientRepresentation::getClientId);
  }
}
