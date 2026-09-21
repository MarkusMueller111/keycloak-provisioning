package de.cronn.keycloak.provisioning.differ;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;

class ClientRepresentationDifferTest {

  @Test
  void detectsAChangedSecretFromTheRenderedDiff() {
    ClientRepresentation before = client("system-a");
    before.setSecret("old");
    ClientRepresentation after = client("system-a");
    after.setSecret("new");

    assertThat(ClientRepresentationDiffer.isSecretChanged(diffOf(before, after))).isTrue();
  }

  /** Keycloak's own attribute mentions the word, but says nothing about the secret itself. */
  @Test
  void ignoresTheSecretCreationTimeAttribute() {
    ClientRepresentation before = client("system-a");
    before.setName("old");
    before.setAttributes(Map.of("client.secret.creation.time", "1726900000"));
    ClientRepresentation after = client("system-a");
    after.setName("new");

    String diff = diffOf(before, after);

    assertThat(diff).contains("client.secret.creation.time");
    assertThat(ClientRepresentationDiffer.isSecretChanged(diff)).isFalse();
  }

  @Test
  void ignoresADiffWithoutAnySecret() {
    ClientRepresentation before = client("system-a");
    before.setName("old");
    ClientRepresentation after = client("system-a");
    after.setName("new");

    assertThat(ClientRepresentationDiffer.isSecretChanged(diffOf(before, after))).isFalse();
  }

  /** The secret stays put while something else changes, so it never reaches the diff. */
  @Test
  void ignoresAnUnchangedSecret() {
    ClientRepresentation before = client("system-a");
    before.setSecret("same");
    before.setName("old");
    ClientRepresentation after = client("system-a");
    after.setSecret("same");
    after.setName("new");

    assertThat(ClientRepresentationDiffer.isSecretChanged(diffOf(before, after))).isFalse();
  }

  private static String diffOf(ClientRepresentation before, ClientRepresentation after) {
    ClientRepresentationDiffer differ =
        new ClientRepresentationDiffer(List.of(before), List.of(after));
    return differ.getElementsToUpdate().getFirst().multiLineDiff();
  }

  private static ClientRepresentation client(String clientId) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(clientId);
    client.setEnabled(true);
    return client;
  }
}
