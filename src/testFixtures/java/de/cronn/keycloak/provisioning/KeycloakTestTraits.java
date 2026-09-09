package de.cronn.keycloak.provisioning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.cronn.assertions.validationfile.junit5.JUnit5ValidationFileAssertions;
import de.cronn.assertions.validationfile.normalization.ValidationNormalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.IdentityProvidersResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserProfileAttributeMetadata;
import org.keycloak.representations.idm.UserProfileMetadata;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPConfig;

public interface KeycloakTestTraits extends JUnit5ValidationFileAssertions {

  // Alphabetic property order is Jackson 3's default; configuring it keeps the validation files
  // stable across the move back to Jackson 3.
  JsonMapper DEFAULT_JSON_MAPPER =
      JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

  ValidationNormalizer idReplacer = KeycloakNormalizers.ids();
  ValidationNormalizer timestampReplacer = KeycloakNormalizers.timestamps();

  static void normalizeRealm(RealmRepresentation realm) {
    if (realm.getOtpSupportedApplications() != null) {
      realm.setOtpSupportedApplications(
          realm.getOtpSupportedApplications().stream().sorted().toList());
    }

    if (realm.getSmtpServer() != null) {
      realm.setSmtpServer(new TreeMap<>(realm.getSmtpServer()));
    }

    Map<String, String> attributes = realm.getAttributes();
    if (attributes != null) {
      // Remove inconsistent attributes, these are already configured on the realm itself and only
      // sometimes show up here too
      removeKeyIfValueIsZero(attributes, "clientOfflineSessionIdleTimeout");
      removeKeyIfValueIsZero(attributes, "clientOfflineSessionMaxLifespan");
      removeKeyIfValueIsZero(attributes, "clientSessionIdleTimeout");
      removeKeyIfValueIsZero(attributes, "clientSessionMaxLifespan");
      realm.setAttributes(new TreeMap<>(attributes));
    }
  }

  static List<IdentityProviderRepresentation> normalizeIdentityProvider(
      List<IdentityProviderRepresentation> providers) {
    return providers.stream()
        .sorted(Comparator.comparing(IdentityProviderRepresentation::getAlias))
        .peek(idp -> idp.setConfig(new TreeMap<>(idp.getConfig())))
        .toList();
  }

  static void removeKeyIfValueIsZero(Map<String, String> attributes, String key) {
    if (Objects.equals(attributes.get(key), "0")) {
      attributes.remove(key);
    }
  }

  static void normalizeClient(ClientRepresentation client) {
    if (client.getDefaultClientScopes() != null) {
      client.getDefaultClientScopes().sort(Comparator.naturalOrder());
    }
    if (client.getOptionalClientScopes() != null) {
      client.getOptionalClientScopes().sort(Comparator.naturalOrder());
    }
    if (client.getRedirectUris() != null) {
      client.getRedirectUris().sort(Comparator.naturalOrder());
    }
    if (client.getAttributes() != null) {
      client.setAttributes(new TreeMap<>(client.getAttributes()));
    }
    if (client.getProtocolMappers() != null) {
      client.getProtocolMappers().sort(Comparator.comparing(ProtocolMapperRepresentation::getName));
    }
  }

  static void sortProtocolMappers(ClientScopeRepresentation c) {
    if (c.getProtocolMappers() != null) {
      c.setProtocolMappers(
          c.getProtocolMappers().stream()
              .sorted(Comparator.comparing(ProtocolMapperRepresentation::getName))
              .peek(p -> p.setConfig(new TreeMap<>(p.getConfig())))
              .toList());
    }
  }

  static UserRepresentation normalizeUser(UserRepresentation user) {
    if (user.getAttributes() != null) {
      TreeMap<String, List<String>> attributes = new TreeMap<>(user.getAttributes());
      // "saml.persistent.name.id.for.*" attribute only exists after login via external IDP and
      // cannot be removed
      attributes.keySet().stream()
          .filter(strings -> strings.startsWith("saml.persistent.name.id.for."))
          .toList()
          .forEach(attributes::remove);
      user.setAttributes(attributes.isEmpty() ? null : attributes);
    }

    normalizeUserProfileMetadata(user);

    return user;
  }

  static void normalizeUserProfileMetadata(UserRepresentation user) {
    UserProfileMetadata userProfileMetadata = user.getUserProfileMetadata();
    if (userProfileMetadata != null) {
      List<UserProfileAttributeMetadata> attributes = userProfileMetadata.getAttributes();
      if (attributes != null) {
        attributes.stream()
            .map(UserProfileAttributeMetadata::getValidators)
            .forEach(KeycloakTestTraits::sortValidatorMaps);
      }
    }
  }

  static void sortValidatorMaps(Map<String, Map<String, Object>> validator) {
    for (Map.Entry<String, Map<String, Object>> entry : validator.entrySet()) {
      LinkedHashMap<String, Object> sortedPattern =
          entry.getValue().entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .collect(
                  Collectors.toMap(
                      Map.Entry::getKey,
                      Map.Entry::getValue,
                      (oldValue, newValue) -> oldValue,
                      LinkedHashMap::new));
      entry.setValue(sortedPattern);
    }
  }

  static UserRepresentation enrichWithRealmRoles(
      UserRepresentation userRepresentation, RealmManager realmManager) {
    List<String> realmRoles =
        realmManager
            .getRealmResource()
            .users()
            .get(userRepresentation.getId())
            .roles()
            .realmLevel()
            .listAll()
            .stream()
            .map(RoleRepresentation::getName)
            .sorted()
            .toList();
    userRepresentation.setRealmRoles(realmRoles);
    return userRepresentation;
  }

  static void enrichWithRealmRoles(GroupRepresentation group, RealmManager realmManager) {
    List<String> realmRoles =
        realmManager.getGroupResource(group.getId()).roles().realmLevel().listAll().stream()
            .map(RoleRepresentation::getName)
            .sorted()
            .toList();
    // Keycloak leaves the field unset for groups without roles; keep that shape, since existing
    // validation files were written with it.
    group.setRealmRoles(realmRoles.isEmpty() ? null : realmRoles);
  }

  static void enrichWithRealmComposites(
      RoleRepresentation roleRepresentation, RolesResource rolesResource) {
    if (roleRepresentation.isComposite()) {
      RoleRepresentation.Composites composites = new RoleRepresentation.Composites();
      Set<String> compositeRoleNames =
          new LinkedHashSet<>(
              rolesResource.get(roleRepresentation.getName()).getRealmRoleComposites().stream()
                  .map(RoleRepresentation::getName)
                  .sorted()
                  .toList());
      composites.setRealm(compositeRoleNames);
      roleRepresentation.setComposites(composites);
    }
  }

  static Map<String, List<String>> getGroupMemberships(
      RealmManager realmManager, List<GroupRepresentation> sortedGroups) {
    return sortedGroups.stream()
        .collect(Collectors.toMap(GroupRepresentation::getName, getGroupMembers(realmManager)));
  }

  static Function<GroupRepresentation, List<String>> getGroupMembers(RealmManager realmManager) {
    return groupRepresentation ->
        realmManager.getGroupResource(groupRepresentation.getId()).members().stream()
            .map(UserRepresentation::getUsername)
            .sorted()
            .toList();
  }

  /**
   * Applied to every snapshot these assertions write. Override to add a project's own normalizers,
   * keeping the Keycloak ones:
   *
   * <pre>{@code
   * @Override
   * public ValidationNormalizer keycloakValidationNormalizer() {
   *   return KeycloakTestTraits.super.keycloakValidationNormalizer().and(myOwn());
   * }
   * }</pre>
   *
   * Compose from {@link KeycloakNormalizers} directly to leave some of them out instead.
   */
  default ValidationNormalizer keycloakValidationNormalizer() {
    return KeycloakNormalizers.forKeycloak(keycloakUrl());
  }

  default void validateKeycloakConfiguration(RealmManager realmManager) {
    validateRequiredActions(realmManager);
    validateFlows(realmManager);

    validateUpdatableKeycloakConfiguration(realmManager);
  }

  default void validateUpdatableKeycloakConfiguration(RealmManager realmManager) {
    validateRealm(realmManager);
    validateRoles(realmManager);
    validateGroups(realmManager);
    validateUsers(realmManager);
    validateUserProfile(realmManager);
    validateClientScopes(realmManager);
    validateClients(realmManager);
    validateServiceAccountUsers(realmManager);
    validateIdentityProviders(realmManager);
  }

  default void validateClientScopes(RealmManager realmManager) {
    List<ClientScopeRepresentation> clientScopes =
        realmManager.getRealmResource().clientScopes().findAll().stream()
            .sorted(Comparator.comparing(ClientScopeRepresentation::getName))
            .peek(KeycloakTestTraits::sortProtocolMappers)
            .toList();

    assertWithJsonFileWithSuffix(clientScopes, keycloakValidationNormalizer(), "clientScopes");
  }

  default void validateUserProfile(RealmManager realmManager) {
    UPConfig configuration =
        realmManager.getRealmResource().users().userProfile().getConfiguration();
    configuration.getAttributes().sort(Comparator.comparing(UPAttribute::getName));
    for (UPAttribute attribute : configuration.getAttributes()) {
      if (attribute.getValidations() != null) {
        attribute.setValidations(new TreeMap<>(attribute.getValidations()));
        for (Map.Entry<String, Map<String, Object>> entry : attribute.getValidations().entrySet()) {
          entry.setValue(new TreeMap<>(entry.getValue()));
        }
      }
    }
    assertWithJsonFileWithSuffix(configuration, idReplacer, "userProfile");
  }

  default void validateRequiredActions(RealmManager realmManager) {
    List<RequiredActionProviderRepresentation> requiredActions =
        realmManager.getRealmResource().flows().getRequiredActions().stream()
            .sorted(Comparator.comparing(RequiredActionProviderRepresentation::getName))
            .toList();

    assertWithJsonFileWithSuffix(requiredActions, idReplacer, "requiredActions");
  }

  default void validateRealm(RealmManager realmManager) {
    RealmRepresentation realm = realmManager.getRealmResource().toRepresentation();
    normalizeRealm(realm);
    assertWithJsonFileWithSuffix(realm, keycloakValidationNormalizer(), "realm");
  }

  default void validateClients(RealmManager realmManager) {
    List<ClientRepresentation> clients =
        realmManager.getRealmResource().clients().findAll().stream()
            .peek(KeycloakTestTraits::normalizeClient)
            .sorted(Comparator.comparing(ClientRepresentation::getClientId))
            .toList();

    assertWithJsonFileWithSuffix(clients, keycloakValidationNormalizer(), "clients");
  }

  default void validateServiceAccountUsers(RealmManager realmManager) {
    List<UserRepresentation> serviceAccountUsers =
        realmManager.getRealmResource().clients().findAll().stream()
            .filter(ClientRepresentation::isServiceAccountsEnabled)
            .map(ClientRepresentation::getClientId)
            .map(realmManager::getClientByClientId)
            .map(Optional::orElseThrow)
            .map(ClientResource::getServiceAccountUser)
            .filter(Objects::nonNull)
            .map(userRepresentation -> enrichWithRealmRoles(userRepresentation, realmManager))
            .map(KeycloakTestTraits::normalizeUser)
            .sorted(Comparator.comparing(UserRepresentation::getUsername))
            .toList();

    assertWithJsonFileWithSuffix(
        serviceAccountUsers, keycloakValidationNormalizer(), "serviceAccountUsers");
  }

  default void validateFlows(RealmManager realmManager) {
    assertWithJsonFileWithSuffix(
        flowsWithExecutions(realmManager, flow -> true), idReplacer, "flows");
  }

  /**
   * Leaves out the flows Keycloak ships itself, worth some 18 kB of JSON before a project adds any
   * of its own.
   */
  default void validateCustomFlows(RealmManager realmManager) {
    assertWithJsonFileWithSuffix(
        flowsWithExecutions(realmManager, flow -> flow.isBuiltIn() != Boolean.TRUE),
        idReplacer,
        "flows");
  }

  private List<FlowWithExecutions> flowsWithExecutions(
      RealmManager realmManager, Predicate<AuthenticationFlowRepresentation> include) {
    return realmManager.getRealmResource().flows().getFlows().stream()
        .filter(include)
        .sorted(Comparator.comparing(AuthenticationFlowRepresentation::getAlias))
        .map(
            flow ->
                new FlowWithExecutions(
                    flow, realmManager.getRealmResource().flows().getExecutions(flow.getAlias())))
        .toList();
  }

  default void validateUsers(RealmManager realmManager) {
    List<UserRepresentation> sortedUser =
        realmManager.getUsers(Integer.MAX_VALUE).stream()
            .map(userRepresentation -> enrichWithRealmRoles(userRepresentation, realmManager))
            .map(KeycloakTestTraits::normalizeUser)
            .sorted(Comparator.comparing(UserRepresentation::getUsername))
            .toList();

    assertWithJsonFileWithSuffix(sortedUser, idReplacer.and(timestampReplacer), "users");
  }

  default void validateRoles(RealmManager realmManager) {
    RolesResource rolesResource = realmManager.getRealmResource().roles();
    List<RoleRepresentation> sortedRoles =
        realmManager.getRoles().stream()
            .peek(
                roleRepresentation -> enrichWithRealmComposites(roleRepresentation, rolesResource))
            .sorted(Comparator.comparing(RoleRepresentation::getName))
            .toList();
    assertWithJsonFileWithSuffix(sortedRoles, idReplacer, "roles");
  }

  default void validateGroups(RealmManager realmManager) {
    List<GroupRepresentation> sortedGroups =
        realmManager.getGroupsResource().groups().stream()
            .peek(group -> enrichWithRealmRoles(group, realmManager))
            .sorted(Comparator.comparing(GroupRepresentation::getName))
            .toList();
    assertWithJsonFileWithSuffix(sortedGroups, idReplacer, "groups");

    Map<String, List<String>> groupMemberships = getGroupMemberships(realmManager, sortedGroups);
    assertWithJsonFileWithSuffix(groupMemberships, idReplacer, "groupMembers");
  }

  default void validateIdentityProviders(RealmManager realmManager) {
    IdentityProvidersResource resource = realmManager.getRealmResource().identityProviders();
    List<IdentityProviderRepresentation> providers = normalizeIdentityProvider(resource.findAll());

    assertWithJsonFileWithSuffix(providers, keycloakValidationNormalizer(), "identity_providers");
    for (IdentityProviderRepresentation provider : providers) {
      List<IdentityProviderMapperRepresentation> mappers =
          resource.get(provider.getAlias()).getMappers().stream()
              .sorted(
                  Comparator.comparing(
                          IdentityProviderMapperRepresentation::getIdentityProviderAlias)
                      .thenComparing(IdentityProviderMapperRepresentation::getName))
              .toList();
      assertWithJsonFileWithSuffix(
          mappers,
          keycloakValidationNormalizer(),
          "identity_provider_mappers_" + provider.getAlias());
    }
  }

  default JsonMapper jsonMapper() {
    return DEFAULT_JSON_MAPPER;
  }

  String keycloakUrl();

  record FlowWithExecutions(
      AuthenticationFlowRepresentation flow,
      List<AuthenticationExecutionInfoRepresentation> executions) {}

  private void assertWithJsonFileWithSuffix(
      Object actual, ValidationNormalizer validationNormalizer, String suffix) {
    String actualAsJsonString = toJson(actual);
    assertWithJsonFileWithSuffix(actualAsJsonString, validationNormalizer, suffix);
  }

  private String toJson(Object actual) {
    try {
      return jsonMapper()
          .writerWithDefaultPrettyPrinter()
          .without(SerializationFeature.FAIL_ON_EMPTY_BEANS)
          .writeValueAsString(actual);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
