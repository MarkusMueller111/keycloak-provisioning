package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;

public class AuthenticationExecutionRepresentationDiffer
    extends AbstractDiffer<AuthenticationExecutionInfoRepresentation> {

  /**
   * What an execution update writes. Everything else on the representation is either server-derived
   * or identity, and is silently ignored: {@code level}, {@code index}, {@code flowId}, {@code
   * configurable} and {@code requirementChoices} are computed from the flow tree, {@code
   * displayName} names the authenticator or the nested flow, and {@code providerId} and {@code
   * alias} identify the execution rather than configure it. Setting {@code authenticationFlow} on a
   * plain execution answers 404.
   *
   * <p>{@code description} is written for a sub-flow, where it belongs to the nested flow, but not
   * for an authenticator, where it comes from the provider.
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
      if (target.getAuthenticationFlow() == Boolean.TRUE) {
        target.setDescription(source.getDescription());
      }
    };
  }

  /**
   * What makes two executions the same one. A sub-flow may be named after an authenticator, so the
   * kind is part of it — comparing names alone would confuse the two. A sub-flow execution has no
   * provider id of its own; it is named after the flow it points at.
   *
   * <p>The position deliberately is not: an execution that moved is the same execution, and
   * including its index would make every reorder a delete plus an add.
   */
  public static String id(AuthenticationExecutionInfoRepresentation execution) {
    return execution.getAuthenticationFlow() == Boolean.TRUE
        ? "sub-flow " + execution.getDisplayName()
        : "authenticator " + execution.getProviderId();
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
