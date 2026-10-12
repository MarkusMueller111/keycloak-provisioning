package de.cronn.keycloak.provisioning.differ;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

public class ProtocolMapperDiffer extends AbstractDiffer<ProtocolMapperRepresentation> {
  private static PropertyUpdater<ProtocolMapperRepresentation>
      protocolMapperRepresentationUpdater() {
    return (target, source) -> {
      target.setName(source.getName());
      target.setProtocol(source.getProtocol());
      target.setProtocolMapper(source.getProtocolMapper());
      // A null config means "not declared": overwriting would wipe what Keycloak holds.
      target.setConfig(
          source.getConfig() == null
              ? target.getConfig()
              : withCreationDefaults(source.getConfig()));
    };
  }

  /**
   * Keycloak fills these in from their sibling flag when a mapper is created, but not on update,
   * which replaces the whole config.
   */
  private static Map<String, String> withCreationDefaults(Map<String, String> config) {
    Map<String, String> result = new LinkedHashMap<>(config);
    copyIfAbsent(result, "access.token.claim", "introspection.token.claim");
    copyIfAbsent(result, "id.token.claim", "userinfo.token.claim");
    return result;
  }

  private static void copyIfAbsent(Map<String, String> config, String from, String to) {
    if (config.containsKey(from) && !config.containsKey(to)) {
      config.put(to, config.get(from));
    }
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
