package de.cronn.keycloak.provisioning;

import org.apache.commons.lang3.Validate;

/**
 * One step of an authentication flow, in the order it should run.
 *
 * <p>Modelled here rather than as Keycloak's {@code AuthenticationExecutionInfoRepresentation},
 * which is its <em>read</em> model: of that type's thirteen settable fields an execution update
 * writes only the requirement and the position, so declaring desired state with it invites setting
 * ten fields that do nothing. For the same reason there is no customizer — nothing else is
 * reachable. Use {@link RealmManager#getRealmResource()} for anything this does not cover.
 */
public sealed interface FlowExecution {

  Requirement requirement();

  /** A step provided by an authenticator, named by its provider id. */
  record Authenticator(String providerId, Requirement requirement) implements FlowExecution {

    public Authenticator {
      Validate.notBlank(providerId, "providerId must not be blank");
      requirement = requirement == null ? Requirement.DISABLED : requirement;
    }

    public Authenticator(String providerId) {
      this(providerId, Requirement.DISABLED);
    }
  }

  /** A nested flow, which has to exist as a flow of its own. */
  record SubFlow(String alias, String description, Requirement requirement)
      implements FlowExecution {

    public SubFlow {
      Validate.notBlank(alias, "alias must not be blank");
      description = description == null ? "" : description;
      requirement = requirement == null ? Requirement.DISABLED : requirement;
    }

    public SubFlow(String alias) {
      this(alias, "", Requirement.DISABLED);
    }
  }

  /**
   * Which choices an authenticator actually accepts is up to the authenticator, so a rejected
   * combination only surfaces when Keycloak is asked.
   */
  enum Requirement {
    REQUIRED,
    ALTERNATIVE,
    DISABLED,
    CONDITIONAL,
  }
}
