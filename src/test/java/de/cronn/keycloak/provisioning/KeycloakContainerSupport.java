package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.KeycloakDefaultConnectionConfig;
import de.cronn.keycloak.provisioning.connection.KeycloakWriteRecorder;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCliConnectionManager;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;
import org.keycloak.admin.client.Keycloak;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Everything the two test base classes share: how a Keycloak container is started and how to reach
 * it. Subclasses decide whether that container is shared or their own.
 *
 * @see SharedKeycloakTest
 * @see OwnKeycloakTest
 */
abstract class KeycloakContainerSupport implements KeycloakTestTraits {

  // Built once and then cached by Docker, so only the first run pays for it.
  private static final ImageFromDockerfile IMAGE =
      new ImageFromDockerfile("keycloak-provisioning-test", false)
          .withDockerfile(Path.of("src/test/resources/keycloak/Dockerfile"));

  protected static final String ADMIN_USERNAME = "temp-admin";
  protected static final String ADMIN_PASSWORD = "temp-admin-password";

  @SuppressWarnings("resource")
  static GenericContainer<?> startKeycloak() {
    GenericContainer<?> container =
        new GenericContainer<>(IMAGE)
            .withExposedPorts(8080, 9000)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USERNAME)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withCommand("start", "--optimized", "--http-enabled=true", "--hostname-strict=false")
            .waitingFor(
                // Health endpoints live on the management port.
                Wait.forHttp("/health/ready")
                    .forPort(9000)
                    .withStartupTimeout(Duration.ofMinutes(5)));
    container.start();
    return container;
  }

  protected abstract GenericContainer<?> keycloak();

  @Override
  public String keycloakUrl() {
    return serverUrl();
  }

  protected String serverUrl() {
    return serverUrl(keycloak());
  }

  static String serverUrl(GenericContainer<?> keycloak) {
    return "http://%s:%d".formatted(keycloak.getHost(), keycloak.getMappedPort(8080));
  }

  /** Connects through the library's own manager, so tests exercise the production wiring. */
  static KeycloakAdminCliConnectionManager adminConnection(GenericContainer<?> keycloak) {
    return new KeycloakAdminCliConnectionManager(
        serverUrl(keycloak), new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD));
  }

  protected abstract Keycloak adminClient();

  /** For what reports no result, like the provisioning client's bootstrap on connect. */
  protected static void assertSendsNoWrites(Runnable action) {
    try (KeycloakWriteRecorder recorder = new KeycloakWriteRecorder()) {
      action.run();
      assertThat(recorder.writes()).as("writes sent to Keycloak").isEmpty();
    }
  }

  /**
   * {@code expectedWrites} as {@code METHOD /path}, with every id as {@code <id>}. Open the
   * connection before calling this, so bootstrapping the provisioning client is not counted against
   * {@code action}.
   */
  protected static ProvisioningResult assertSendsOnlyWrites(
      Supplier<ProvisioningResult> action, String... expectedWrites) {
    try (KeycloakWriteRecorder recorder = new KeycloakWriteRecorder()) {
      ProvisioningResult result = action.get();
      assertThat(recorder.writes())
          .as("writes sent to Keycloak")
          .containsExactlyInAnyOrder(expectedWrites);
      return result;
    }
  }

  /** Neither sends a write nor reports a change. */
  protected static void assertChangesNothing(Supplier<ProvisioningResult> action) {
    assertThat(assertSendsOnlyWrites(action).changes()).as("reported changes").isEmpty();
  }

  protected DefaultKeycloakConnectionManager connectionManager() {
    return new DefaultKeycloakConnectionManager(
        new KeycloakDefaultConnectionConfig(
            serverUrl(),
            new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD),
            new KeycloakProvisioningClientConfig("keycloak-provisioning", "secret")));
  }
}
