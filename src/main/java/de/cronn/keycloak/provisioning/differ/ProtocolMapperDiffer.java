package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

public class ProtocolMapperDiffer extends AbstractDiffer<ProtocolMapperRepresentation> {
  private static PropertyUpdater<ProtocolMapperRepresentation>
      protocolMapperRepresentationUpdater() {
    return (target, source) -> {
      target.setName(source.getName());
      target.setProtocol(source.getProtocol());
      target.setProtocolMapper(source.getProtocolMapper());
      target.setConfig(source.getConfig());
    };
  }

  public ProtocolMapperDiffer(
      List<ProtocolMapperRepresentation> target, List<ProtocolMapperRepresentation> source) {
    super(
        target,
        source,
        protocolMapperRepresentationUpdater(),
        ProtocolMapperRepresentation::getName);
  }
}
