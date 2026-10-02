package de.cronn.keycloak.provisioning;

import java.util.ArrayList;
import java.util.List;

/**
 * What a provisioning call changed. Holds only the writes this library reports, so the empty result
 * of an unchanged rerun is the common case. The provisioning client's bootstrap on connect is not
 * included.
 */
public record ProvisioningResult(List<Change> changes) {

  public ProvisioningResult {
    changes = List.copyOf(changes);
  }

  public static ProvisioningResult none() {
    return new ProvisioningResult(List.of());
  }

  public boolean hasChanges() {
    return !changes.isEmpty();
  }

  public ProvisioningResult plus(ProvisioningResult other) {
    List<Change> combined = new ArrayList<>(changes);
    combined.addAll(other.changes);
    return new ProvisioningResult(combined);
  }
}
