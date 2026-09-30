package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.ClientScopeRepresentation;

public class ClientScopeRepresentationDiffer extends AbstractDiffer<ClientScopeRepresentation> {
  private static PropertyUpdater<ClientScopeRepresentation> clientScopeRepresentationUpdater() {
    return (target, source) -> {
      target.setName(source.getName());
      target.setDescription(source.getDescription());
      target.setProtocol(source.getProtocol());
      target.setAttributes(source.getAttributes());
      target.setProtocolMappers(source.getProtocolMappers());
    };
  }

  public ClientScopeRepresentationDiffer(
      List<ClientScopeRepresentation> target, List<ClientScopeRepresentation> source) {
    super(target, source, clientScopeRepresentationUpdater(), ClientScopeRepresentation::getName);
  }
}
