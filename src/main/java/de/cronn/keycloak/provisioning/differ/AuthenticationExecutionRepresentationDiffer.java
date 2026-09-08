package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;

public class AuthenticationExecutionRepresentationDiffer
    extends AbstractDiffer<AuthenticationExecutionInfoRepresentation> {

  private static PropertyUpdater<AuthenticationExecutionInfoRepresentation>
      executionRepresentationUpdater() {
    return (target, source) -> target.setRequirement(source.getRequirement());
  }

  /**
   * Sub-flows carry no provider id, and executions carry no alias, so the two are named
   * differently. The position is deliberately not part of this: an execution that moved is the same
   * execution, and including its index would make every reorder a delete plus an add.
   */
  private static String id(AuthenticationExecutionInfoRepresentation execution) {
    if (execution.getAuthenticationFlow() == Boolean.TRUE) {
      return execution.getDisplayName() != null ? execution.getDisplayName() : execution.getAlias();
    }
    return execution.getProviderId();
  }

  public AuthenticationExecutionRepresentationDiffer(
      List<AuthenticationExecutionInfoRepresentation> target,
      List<AuthenticationExecutionInfoRepresentation> source) {
    super(
        target,
        source,
        executionRepresentationUpdater(),
        AuthenticationExecutionRepresentationDiffer::id);
  }
}
