package de.cronn.keycloak.provisioning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.Validate;

/**
 * A group and its sub-groups, as the full desired state of that subtree.
 *
 * <p>Modelled here rather than as Keycloak's {@code GroupRepresentation}, which ignores {@code
 * realmRoles} on create and never fills {@code subGroups} when read. For anything not covered, use
 * {@link RealmManager#findGroupByPath(String)} and {@link RealmManager#getRealmResource()}.
 *
 * @param name has to carry the group prefix of the {@link ManagedNaming}, at every level
 * @param description {@code null} means none
 * @param clientRoles role names by client id
 */
public record GroupConfig(
    String name,
    String description,
    Map<String, List<String>> attributes,
    List<String> realmRoles,
    Map<String, List<String>> clientRoles,
    List<GroupConfig> subGroups) {

  public GroupConfig {
    Validate.notBlank(name, "group name must not be blank");
    // Keycloak joins a path with "/" without escaping it, so such a group's path is ambiguous.
    Validate.isTrue(!name.contains("/"), "group name must not contain '/', but was '%s'", name);
    attributes = copyOfListMap(attributes);
    clientRoles = copyOfListMap(clientRoles);
    realmRoles = realmRoles == null ? List.of() : List.copyOf(realmRoles);
    subGroups = subGroups == null ? List.of() : List.copyOf(subGroups);
  }

  private static Map<String, List<String>> copyOfListMap(Map<String, List<String>> map) {
    if (map == null) {
      return Map.of();
    }
    Map<String, List<String>> copy = new LinkedHashMap<>();
    map.forEach((key, values) -> copy.put(key, List.copyOf(values)));
    return Collections.unmodifiableMap(copy);
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static final class Builder {

    private final String name;
    private String description;
    private final Map<String, List<String>> attributes = new LinkedHashMap<>();
    private final List<String> realmRoles = new ArrayList<>();
    private final Map<String, List<String>> clientRoles = new LinkedHashMap<>();
    private final List<GroupConfig> subGroups = new ArrayList<>();

    private Builder(String name) {
      this.name = name;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder attribute(String key, String... values) {
      attributes.put(Objects.requireNonNull(key), List.of(values));
      return this;
    }

    public Builder realmRoles(String... roleNames) {
      realmRoles.addAll(List.of(roleNames));
      return this;
    }

    public Builder clientRoles(String clientId, String... roleNames) {
      clientRoles
          .computeIfAbsent(Objects.requireNonNull(clientId), id -> new ArrayList<>())
          .addAll(List.of(roleNames));
      return this;
    }

    public Builder subGroups(GroupConfig... groups) {
      subGroups.addAll(List.of(groups));
      return this;
    }

    public GroupConfig build() {
      return new GroupConfig(name, description, attributes, realmRoles, clientRoles, subGroups);
    }
  }
}
