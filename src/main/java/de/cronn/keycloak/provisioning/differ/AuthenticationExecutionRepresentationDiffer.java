package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;

public class AuthenticationExecutionRepresentationDiffer
    extends AbstractDiffer<AuthenticationExecutionInfoRepresentation> {

  /**
   * The only two fields an execution update writes. Everything else on the representation is either
   * server-derived or identity, and is silently ignored: {@code level}, {@code index}, {@code
   * flowId}, {@code configurable} and {@code requirementChoices} are computed from the flow tree,
   * {@code displayName} and {@code description} come from the authenticator provider, and {@code
   * providerId} and {@code alias} identify the execution rather than configure it. Setting {@code
   * authenticationFlow} on a plain execution answers 404.
   *
   * <p>Order follows {@code priority}; Keycloak recomputes {@code index} from it on read. The
   * representation's {@code id} selects which execution is written, so it must stay the one read
   * from the server.
   */
  private static PropertyUpdater<AuthenticationExecutionInfoRepresentation>
      executionRepresentationUpdater() {
    return (target, source) -> {
      target.setRequirement(source.getRequirement());
      target.setPriority(source.getPriority());
    };
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
