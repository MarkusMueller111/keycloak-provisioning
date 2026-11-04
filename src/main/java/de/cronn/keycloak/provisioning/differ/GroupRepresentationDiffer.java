package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.GroupRepresentation;

/**
 * Diffs the groups of one level of the tree. Sub-groups and role mappings are left out: an update
 * ignores both, so {@code RealmManager} provisions them through their own endpoints.
 */
public class GroupRepresentationDiffer extends AbstractDiffer<GroupRepresentation> {

  private static PropertyUpdater<GroupRepresentation> groupRepresentationUpdater() {
    return (target, source) -> {
      target.setName(source.getName());
      // Unlike most fields, an update clears the description when sent null.
      target.setDescription(source.getDescription());
      // An update replaces the whole map; only null leaves it untouched.
      target.setAttributes(source.getAttributes());
    };
  }

  public GroupRepresentationDiffer(
      List<GroupRepresentation> target, List<GroupRepresentation> source) {
    super(target, source, groupRepresentationUpdater(), GroupRepresentation::getName);
  }
}
