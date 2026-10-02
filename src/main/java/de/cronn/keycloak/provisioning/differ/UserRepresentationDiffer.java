package de.cronn.keycloak.provisioning.differ;

import static de.cronn.keycloak.provisioning.differ.Declared.orDefault;
import static de.cronn.keycloak.provisioning.differ.Declared.orDefaultIgnoringOrder;
import static de.cronn.keycloak.provisioning.differ.Declared.orUnset;

import java.util.List;
import java.util.Map;
import org.keycloak.representations.idm.UserRepresentation;

public class UserRepresentationDiffer extends AbstractDiffer<UserRepresentation> {
  private static PropertyUpdater<UserRepresentation> userRepresentationUpdater() {
    return (target, source) -> {
      target.setUsername(source.getUsername());
      target.setFirstName(orUnset(source.getFirstName(), target.getFirstName()));
      target.setLastName(orUnset(source.getLastName(), target.getLastName()));
      target.setEmail(orUnset(source.getEmail(), target.getEmail()));
      target.setEmailVerified(orDefault(source.isEmailVerified(), target.isEmailVerified(), false));
      target.setEnabled(orDefault(source.isEnabled(), target.isEnabled(), false));
      target.setRequiredActions(
          orDefaultIgnoringOrder(
              source.getRequiredActions(), target.getRequiredActions(), List.of()));
      // An update replaces the attributes the user profile manages, so {} removes them all.
      target.setAttributes(orDefault(source.getAttributes(), target.getAttributes(), Map.of()));
      // Credentials are left out: Keycloak never returns their value, so a diff could not see a
      // change. TestUserProvisioner applies them separately whenever a desired user carries any.
    };
  }

  public UserRepresentationDiffer(
      List<UserRepresentation> target, List<UserRepresentation> source) {
    super(target, source, userRepresentationUpdater(), UserRepresentation::getUsername);
  }
}
