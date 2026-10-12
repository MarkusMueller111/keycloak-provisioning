package de.cronn.keycloak.provisioning.differ;

import static de.cronn.keycloak.provisioning.differ.Declared.mergedMap;
import static de.cronn.keycloak.provisioning.differ.Declared.orUnset;

import java.util.List;
import java.util.Map;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientScopeRepresentationDiffer extends AbstractDiffer<ClientScopeRepresentation> {

  private static final Logger log = LoggerFactory.getLogger(ClientScopeRepresentationDiffer.class);

  /**
   * How Keycloak treats these attributes when they are absent. A scope's update can overwrite an
   * attribute but never remove it, so an attribute that is no longer declared is set to this.
   */
  private static final Map<String, String> ATTRIBUTE_DEFAULTS =
      Map.of("include.in.token.scope", "true");

  private static PropertyUpdater<ClientScopeRepresentation> clientScopeRepresentationUpdater() {
    return (target, source) -> {
      target.setName(source.getName());
      target.setDescription(orUnset(source.getDescription(), target.getDescription()));
      target.setProtocol(source.getProtocol());
      warnAboutAttributesBlanked(target, source);
      target.setAttributes(
          mergedMap(
              source.getAttributes(),
              target.getAttributes(),
              Map.of(),
              key -> false,
              ClientScopeRepresentationDiffer::resetValue));
      // Protocol mappers are left out: a scope's update ignores them, so RealmManager provisions
      // them through the mappers endpoint afterwards.
    };
  }

  private static String resetValue(String attribute) {
    return ATTRIBUTE_DEFAULTS.getOrDefault(attribute, "");
  }

  private static void warnAboutAttributesBlanked(
      ClientScopeRepresentation target, ClientScopeRepresentation source) {
    if (target.getAttributes() == null) {
      return;
    }
    Map<String, String> declared =
        source.getAttributes() != null ? source.getAttributes() : Map.of();
    target
        .getAttributes()
        .forEach(
            (key, value) -> {
              if (!declared.containsKey(key) && resetValue(key).isEmpty() && !value.isEmpty()) {
                log.warn(
                    "Client scope '{}' no longer declares attribute '{}'. Keycloak cannot remove a"
                        + " scope attribute, so it is set to \"\" instead.",
                    source.getName(),
                    key);
              }
            });
  }

  public ClientScopeRepresentationDiffer(
      List<ClientScopeRepresentation> target, List<ClientScopeRepresentation> source) {
    super(target, source, clientScopeRepresentationUpdater(), ClientScopeRepresentation::getName);
  }
}
