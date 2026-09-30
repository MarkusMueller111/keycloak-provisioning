package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;

/** Behaviour of provisionFlows and provisionExecutions. */
class FlowProvisioningTest extends SharedKeycloakTest {

  private static final String FLOW = "test-flow";

  @Test
  void createsAFlowThatDoesNotExistYet() {
    withRealmManager(
        realmManager -> realmManager.provisionFlows(List.of(flow(FLOW, "a test flow"))));

    assertThat(flowAliases()).contains(FLOW);
    assertThat(findFlow(FLOW).getDescription()).isEqualTo("a test flow");
  }

  @Test
  void updatesAFlowThatAlreadyExists() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "first")));
          realmManager.provisionFlows(List.of(flow(FLOW, "second")));
        });

    assertThat(findFlow(FLOW).getDescription()).isEqualTo("second");
    assertThat(flowAliases().stream().filter(FLOW::equals)).hasSize(1);
  }

  @Test
  void deletesFlowsThatAreNoLongerConfigured() {
    withRealmManager(
        realmManager ->
            realmManager.provisionFlows(
                List.of(flow("kept", "kept"), flow("orphan", "no longer configured"))));

    withRealmManager(realmManager -> realmManager.provisionFlows(List.of(flow("kept", "kept"))));

    assertThat(flowAliases()).contains("kept").doesNotContain("orphan");
  }

  /** Keycloak's own flows are out of scope, however little is declared. */
  @Test
  void neverDeletesBuiltInFlows() {
    withRealmManager(realmManager -> realmManager.provisionFlows(List.of(flow(FLOW, "only this"))));

    assertThat(flowAliases()).contains("browser", "direct grant", "registration", FLOW);
  }

  @Test
  void addsConfiguredExecutionsToAFlow() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.provisionExecutions(Map.of(FLOW, List.of(execution("auth-cookie"))));
        });

    withRealmManager(this::validateCustomFlows);
  }

  @Test
  void updatesTheRequirementOfAnExistingExecution() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.provisionExecutions(Map.of(FLOW, List.of(execution("auth-cookie"))));

          FlowExecution required =
              new FlowExecution.Authenticator("auth-cookie", FlowExecution.Requirement.REQUIRED);
          realmManager.provisionExecutions(Map.of(FLOW, List.of(required)));
        });

    withRealmManager(this::validateCustomFlows);
  }

  /** Each entry is the full desired content of its flow, so a dropped execution is removed. */
  @Test
  void doesNotKeepExecutionsThatAreNoLongerConfigured() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.provisionExecutions(
              Map.of(FLOW, List.of(execution("auth-cookie"), execution("auth-otp-form"))));

          realmManager.provisionExecutions(Map.of(FLOW, List.of(execution("auth-cookie"))));
        });

    withRealmManager(this::validateCustomFlows);
  }

  /** The declared order is the desired order, and reordering moves rather than recreates. */
  @Test
  void reordersExecutionsWithoutRecreatingThem() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
          FlowExecution cookie =
              new FlowExecution.Authenticator("auth-cookie", FlowExecution.Requirement.REQUIRED);
          realmManager.provisionExecutions(
              Map.of(FLOW, List.of(cookie, execution("auth-otp-form"))));
        });

    String idBeforeReorder = findExecution(FLOW, "auth-cookie").getId();

    withRealmManager(
        realmManager -> {
          FlowExecution cookie =
              new FlowExecution.Authenticator("auth-cookie", FlowExecution.Requirement.REQUIRED);
          realmManager.provisionExecutions(
              Map.of(FLOW, List.of(execution("auth-otp-form"), cookie)));
        });

    assertThat(executionProviderIds(FLOW)).containsExactly("auth-otp-form", "auth-cookie");
    // Same execution, so its id survives and the requirement it carried is untouched.
    assertThat(findExecution(FLOW, "auth-cookie").getId()).isEqualTo(idBeforeReorder);
    assertThat(findExecution(FLOW, "auth-cookie").getRequirement()).isEqualTo("REQUIRED");
  }

  /** The fence is Keycloak's built-in flag, not our prefix, so a console-made flow is in scope. */
  @Test
  void deletesAFlowCreatedOutsideThisLibrary() {
    AuthenticationFlowRepresentation byHand = flow("made-in-the-console", "not ours");
    adminClient().realm(REALM).flows().createFlow(byHand);
    assertThat(flowAliases()).contains("made-in-the-console");

    withRealmManager(rm -> rm.provisionFlows(List.of(flow(FLOW, "a test flow"))));

    assertThat(flowAliases()).contains(FLOW).doesNotContain("made-in-the-console");
  }

  /** Keycloak appends a new execution, so its declared position only holds if we write it back. */
  @Test
  void placesANewExecutionAtItsDeclaredPosition() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.provisionExecutions(Map.of(FLOW, List.of(execution("auth-otp-form"))));
        });

    withRealmManager(
        realmManager ->
            realmManager.provisionExecutions(
                Map.of(FLOW, List.of(execution("auth-cookie"), execution("auth-otp-form")))));

    assertThat(executionProviderIds(FLOW)).containsExactly("auth-cookie", "auth-otp-form");
  }

  /** Each entry is applied to the flow it names, independently of the others. */
  @Test
  void appliesEachEntryToTheFlowItNames() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(
              List.of(
                  flow(FLOW, "a test flow"),
                  flow("sub-a", "sub flow a"),
                  flow("sub-b", "sub flow b")));

          Map<String, List<FlowExecution>> perSubFlow = new LinkedHashMap<>();
          perSubFlow.put("sub-a", List.of(execution("auth-cookie")));
          perSubFlow.put("sub-b", List.of(execution("auth-otp-form")));
          realmManager.provisionExecutions(perSubFlow);
        });

    withRealmManager(this::validateCustomFlows);
  }

  /** A sub-flow comes back named by displayName, so matching it again is the fragile part. */
  @Test
  void nestsASubFlowAndKeepsItAcrossRuns() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.provisionExecutions(
              Map.of(FLOW, List.of(new FlowExecution.SubFlow("nested", "a nested flow", null))));
        });

    String idAfterFirstRun = findSubFlow(FLOW, "nested").getId();

    withRealmManager(
        realmManager ->
            realmManager.provisionExecutions(
                Map.of(FLOW, List.of(new FlowExecution.SubFlow("nested", "a nested flow", null)))));

    assertThat(findSubFlow(FLOW, "nested").getId()).isEqualTo(idAfterFirstRun);
  }

  /** Two sub-flows share a null provider id, so they must be told apart by name. */
  @Test
  void keepsTwoSubFlowsOfTheSameParentApart() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.provisionExecutions(
              Map.of(
                  FLOW,
                  List.of(
                      new FlowExecution.SubFlow(
                          "first-sub", "a", FlowExecution.Requirement.REQUIRED),
                      new FlowExecution.SubFlow(
                          "second-sub", "b", FlowExecution.Requirement.DISABLED))));
        });

    assertThat(findSubFlow(FLOW, "first-sub").getRequirement()).isEqualTo("REQUIRED");
    assertThat(findSubFlow(FLOW, "second-sub").getRequirement()).isEqualTo("DISABLED");
  }

  /**
   * A sub-flow may be named after an authenticator; the two must not be mistaken for each other.
   */
  @Test
  void tellsASubFlowApartFromAnAuthenticatorOfTheSameName() {
    withRealmManager(
        realmManager -> {
          realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
          realmManager.provisionExecutions(
              Map.of(
                  FLOW,
                  List.of(
                      new FlowExecution.Authenticator(
                          "auth-cookie", FlowExecution.Requirement.REQUIRED),
                      new FlowExecution.SubFlow(
                          "auth-cookie", "a sub-flow of the same name", null))));
        });

    assertThat(findExecution(FLOW, "auth-cookie").getRequirement()).isEqualTo("REQUIRED");
    assertThat(findSubFlow(FLOW, "auth-cookie").getAuthenticationFlow()).isTrue();
  }

  /**
   * Keycloak permits it, but two identical executions cannot be told apart on the next run, so this
   * fails before anything is written rather than silently recreating them each time.
   */
  @Test
  void rejectsTheSameAuthenticatorTwiceInOneFlow() {
    assertThatThrownBy(
            () ->
                withRealmManager(
                    realmManager -> {
                      realmManager.provisionFlows(List.of(flow(FLOW, "a test flow")));
                      realmManager.provisionExecutions(
                          Map.of(
                              FLOW,
                              List.of(
                                  new FlowExecution.Authenticator("auth-cookie"),
                                  new FlowExecution.Authenticator("auth-cookie"))));
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(FLOW)
        .hasMessageContaining("auth-cookie");

    assertThat(executionProviderIds(FLOW)).isEmpty();
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

  private static FlowExecution execution(String providerId) {
    return new FlowExecution.Authenticator(providerId);
  }

  private List<String> executionProviderIds(String flowAlias) {
    return adminClient().realm(REALM).flows().getExecutions(flowAlias).stream()
        .map(AuthenticationExecutionInfoRepresentation::getProviderId)
        .toList();
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

  private AuthenticationExecutionInfoRepresentation findSubFlow(
      String flowAlias, String displayName) {
    return adminClient().realm(REALM).flows().getExecutions(flowAlias).stream()
        .filter(e -> displayName.equals(e.getDisplayName()))
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
