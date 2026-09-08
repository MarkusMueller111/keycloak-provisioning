package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;

/** Behaviour of configureFlows and addOrUpdateExecutions. */
class FlowProvisioningTest extends SharedKeycloakTest {

  private static final String FLOW = "test-flow";

  @Test
  void createsAFlowThatDoesNotExistYet() {
    withRealmManager(
        realmManager -> realmManager.configureFlows(List.of(flow(FLOW, "a test flow"))));

    assertThat(flowAliases()).contains(FLOW);
    assertThat(findFlow(FLOW).getDescription()).isEqualTo("a test flow");
  }

  @Test
  void updatesAFlowThatAlreadyExists() {
    withRealmManager(
        realmManager -> {
          realmManager.configureFlows(List.of(flow(FLOW, "first")));
          realmManager.configureFlows(List.of(flow(FLOW, "second")));
        });

    assertThat(findFlow(FLOW).getDescription()).isEqualTo("second");
    assertThat(flowAliases().stream().filter(FLOW::equals)).hasSize(1);
  }

  @Test
  void deletesFlowsThatAreNoLongerConfigured() {
    withRealmManager(
        realmManager ->
            realmManager.configureFlows(
                List.of(flow("kept", "kept"), flow("orphan", "no longer configured"))));

    withRealmManager(realmManager -> realmManager.configureFlows(List.of(flow("kept", "kept"))));

    assertThat(flowAliases()).contains("kept").doesNotContain("orphan");
  }

  /** Keycloak's own flows are out of scope, however little is declared. */
  @Test
  void neverDeletesBuiltInFlows() {
    withRealmManager(realmManager -> realmManager.configureFlows(List.of(flow(FLOW, "only this"))));

    assertThat(flowAliases()).contains("browser", "direct grant", "registration", FLOW);
  }

  @Test
  void addsConfiguredExecutionsToAFlow() {
    withRealmManager(
        realmManager -> {
          realmManager.configureFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.addOrUpdateExecutions(FLOW, Map.of(FLOW, List.of(execution("auth-cookie"))));
        });

    withRealmManager(this::validateCustomFlows);
  }

  @Test
  void updatesTheRequirementOfAnExistingExecution() {
    withRealmManager(
        realmManager -> {
          realmManager.configureFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.addOrUpdateExecutions(FLOW, Map.of(FLOW, List.of(execution("auth-cookie"))));

          AuthenticationExecutionInfoRepresentation required = execution("auth-cookie");
          required.setRequirement("REQUIRED");
          realmManager.addOrUpdateExecutions(FLOW, Map.of(FLOW, List.of(required)));
        });

    withRealmManager(this::validateCustomFlows);
  }

  /** Each entry is the full desired content of its flow, so a dropped execution is removed. */
  @Test
  void doesNotKeepExecutionsThatAreNoLongerConfigured() {
    withRealmManager(
        realmManager -> {
          realmManager.configureFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.addOrUpdateExecutions(
              FLOW, Map.of(FLOW, List.of(execution("auth-cookie"), execution("auth-otp-form"))));

          realmManager.addOrUpdateExecutions(FLOW, Map.of(FLOW, List.of(execution("auth-cookie"))));
        });

    withRealmManager(this::validateCustomFlows);
  }

  /** A reorder moves the executions rather than deleting and re-adding them. */
  @Test
  void reordersExecutionsWithoutRecreatingThem() {
    withRealmManager(
        realmManager -> {
          realmManager.configureFlows(List.of(flow(FLOW, "a test flow")));
          AuthenticationExecutionInfoRepresentation cookie = execution("auth-cookie");
          cookie.setRequirement("REQUIRED");
          realmManager.addOrUpdateExecutions(
              FLOW, Map.of(FLOW, List.of(cookie, execution("auth-otp-form"))));
        });

    String idBeforeReorder = findExecution(FLOW, "auth-cookie").getId();

    withRealmManager(
        realmManager -> {
          AuthenticationExecutionInfoRepresentation cookie = execution("auth-cookie");
          cookie.setRequirement("REQUIRED");
          realmManager.addOrUpdateExecutions(
              FLOW, Map.of(FLOW, List.of(execution("auth-otp-form"), cookie)));
        });

    // Same execution, so its id survives and the requirement it carried is untouched.
    assertThat(findExecution(FLOW, "auth-cookie").getId()).isEqualTo(idBeforeReorder);
    assertThat(findExecution(FLOW, "auth-cookie").getRequirement()).isEqualTo("REQUIRED");
  }

  /** Each entry is applied to the flow it names, independently of the others. */
  @Test
  void appliesEachEntryToTheFlowItNames() {
    withRealmManager(
        realmManager -> {
          realmManager.configureFlows(
              List.of(
                  flow(FLOW, "a test flow"),
                  flow("sub-a", "sub flow a"),
                  flow("sub-b", "sub flow b")));

          Map<String, List<AuthenticationExecutionInfoRepresentation>> perSubFlow =
              new LinkedHashMap<>();
          perSubFlow.put("sub-a", List.of(execution("auth-cookie")));
          perSubFlow.put("sub-b", List.of(execution("auth-otp-form")));
          realmManager.addOrUpdateExecutions(FLOW, perSubFlow);
        });

    withRealmManager(this::validateCustomFlows);
  }

  private static AuthenticationFlowRepresentation flow(String alias, String description) {
    AuthenticationFlowRepresentation flow = new AuthenticationFlowRepresentation();
    flow.setAlias(alias);
    flow.setDescription(description);
    flow.setProviderId("basic-flow");
    flow.setTopLevel(true);
    flow.setBuiltIn(false);
    return flow;
  }

  private static AuthenticationExecutionInfoRepresentation execution(String providerId) {
    AuthenticationExecutionInfoRepresentation execution =
        new AuthenticationExecutionInfoRepresentation();
    execution.setProviderId(providerId);
    execution.setRequirement("DISABLED");
    return execution;
  }

  private List<String> flowAliases() {
    return adminClient().realm(REALM).flows().getFlows().stream()
        .map(AuthenticationFlowRepresentation::getAlias)
        .toList();
  }

  private AuthenticationFlowRepresentation findFlow(String alias) {
    return adminClient().realm(REALM).flows().getFlows().stream()
        .filter(f -> alias.equals(f.getAlias()))
        .findFirst()
        .orElseThrow();
  }

  private AuthenticationExecutionInfoRepresentation findExecution(
      String flowAlias, String providerId) {
    return adminClient().realm(REALM).flows().getExecutions(flowAlias).stream()
        .filter(e -> providerId.equals(e.getProviderId()))
        .findFirst()
        .orElseThrow();
  }
}
