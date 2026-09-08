package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import java.util.TreeSet;
import org.keycloak.representations.userprofile.config.UPAttribute;

public class UserProfileAttributeDiffer extends AbstractDiffer<UPAttribute> {

  public UserProfileAttributeDiffer(List<UPAttribute> target, List<UPAttribute> source) {
    super(
        sortPermissions(target),
        sortPermissions(source),
        userProfileAttributeUpdater(),
        UPAttribute::getName);
  }

  private static List<UPAttribute> sortPermissions(List<UPAttribute> attributes) {
    attributes.stream()
        .map(UPAttribute::getPermissions)
        .forEach(
            permission -> {
              permission.setView(new TreeSet<>(permission.getView()));
              permission.setEdit(new TreeSet<>(permission.getEdit()));
            });
    return attributes;
  }

  private static PropertyUpdater<UPAttribute> userProfileAttributeUpdater() {
    return (target, source) -> {
      target.setDisplayName(source.getDisplayName());
      target.setGroup(source.getGroup());
      target.setPermissions(source.getPermissions());
      target.setRequired(source.getRequired());
      target.setValidations(source.getValidations());
    };
  }
}
