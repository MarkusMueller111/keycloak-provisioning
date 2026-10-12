package de.cronn.keycloak.provisioning.differ;

import static de.cronn.keycloak.provisioning.differ.Declared.orUnset;

import java.util.List;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;

public class AuthenticationFlowRepresentationDiffer
    extends AbstractDiffer<AuthenticationFlowRepresentation> {

  /**
   * What a flow update writes. {@code authenticationExecutions} is left out: the update ignores it,
   * so RealmManager provisions a flow's executions through their own endpoint afterwards. The
   * representation's {@code id} selects which flow is written, so it must stay the one read from
   * the server.
   */
  private static PropertyUpdater<AuthenticationFlowRepresentation> flowRepresentationUpdater() {
    return (target, source) -> {
      target.setDescription(orUnset(source.getDescription(), target.getDescription()));
      target.setProviderId(source.getProviderId());
      target.setTopLevel(source.isTopLevel());
      target.setBuiltIn(source.isBuiltIn());
    };
  }

  public AuthenticationFlowRepresentationDiffer(
      List<AuthenticationFlowRepresentation> target,
      List<AuthenticationFlowRepresentation> source) {
    super(target, source, flowRepresentationUpdater(), AuthenticationFlowRepresentation::getAlias);
  }
}
