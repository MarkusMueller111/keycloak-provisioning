package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import java.util.Objects;
import java.util.Set;
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

  /** An attribute created over REST may carry no permissions at all, or neither set. */
  private static List<UPAttribute> sortPermissions(List<UPAttribute> attributes) {
    attributes.stream()
        .map(UPAttribute::getPermissions)
        .filter(Objects::nonNull)
        .forEach(
            permission -> {
              permission.setView(sorted(permission.getView()));
              permission.setEdit(sorted(permission.getEdit()));
            });
    return attributes;
  }

  private static Set<String> sorted(Set<String> roles) {
    return roles == null ? null : new TreeSet<>(roles);
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
