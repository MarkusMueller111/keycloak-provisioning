package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.UserRepresentation;

public class UserRepresentationDiffer extends AbstractDiffer<UserRepresentation> {
  private static PropertyUpdater<UserRepresentation> userRepresentationUpdater() {
    return (target, source) -> {
      target.setUsername(source.getUsername());
      target.setFirstName(source.getFirstName());
      target.setLastName(source.getLastName());
      target.setEmail(source.getEmail());
      target.setEmailVerified(source.isEmailVerified());
      target.setEnabled(source.isEnabled());
      target.setRequiredActions(source.getRequiredActions());
      target.setAttributes(source.getAttributes());
      // Credentials are left out: Keycloak never returns their value, so a diff could not see a
      // change. TestUserProvisioner applies them separately whenever a desired user carries any.
    };
  }

  public UserRepresentationDiffer(
      List<UserRepresentation> target, List<UserRepresentation> source) {
    super(target, source, userRepresentationUpdater(), UserRepresentation::getUsername);
  }
}
