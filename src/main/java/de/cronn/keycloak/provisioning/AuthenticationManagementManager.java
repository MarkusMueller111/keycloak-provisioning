package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.differ.AbstractDiffer;
import de.cronn.keycloak.provisioning.differ.Differ;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.Validate;
import org.keycloak.admin.client.resource.AuthenticationManagementResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderSimpleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures required actions on a realm's {@link AuthenticationManagementResource}.
 *
 * <p>Required actions are pre-registered, not configured actions are unregistered
 */
public class AuthenticationManagementManager {

  private static final Logger log = LoggerFactory.getLogger(AuthenticationManagementManager.class);

  private final RealmResource realm;

  public AuthenticationManagementManager(RealmResource realm) {
    this.realm = Objects.requireNonNull(realm);
  }

  public ProvisioningResult provisionRequiredActions(AuthenticationManagementConfig config) {
    AuthenticationManagementResource authentication = realm.flows();

    List<AuthenticationManagementConfig.RequiredAction> requiredActions = config.requiredActions();
    if (requiredActions.isEmpty()) {
      return ProvisioningResult.none();
    }
    assertRequiredActionsExist(authentication, requiredActions);

    Changes changes = new Changes(log, realm.toRepresentation().getRealm());
    registerUnregisteredActions(authentication, requiredActions, changes);

    Map<String, RequiredActionProviderRepresentation> existingByProviderId =
        authentication.getRequiredActions().stream()
            .collect(Collectors.toMap(RequiredActionProviderRepresentation::getProviderId, r -> r));

    int priority = 10;
    for (AuthenticationManagementConfig.RequiredAction requiredAction : requiredActions) {
      updateRequiredActionIfChanged(authentication, requiredAction, priority, changes);
      priority += 10;
      existingByProviderId.remove(requiredAction.providerId());
    }

    for (RequiredActionProviderRepresentation unmanaged : existingByProviderId.values()) {
      changes.removed(Target.REQUIRED_ACTION, unmanaged.getAlias());
      authentication.removeRequiredAction(unmanaged.getAlias());
    }
    return changes.result();
  }

  /**
   * Before the first write: a provider id Keycloak does not know would otherwise only fail once the
   * actions before it had been registered and updated.
   */
  private static void assertRequiredActionsExist(
      AuthenticationManagementResource authentication,
      List<AuthenticationManagementConfig.RequiredAction> requiredActions) {
    Set<String> known =
        Stream.concat(
                authentication.getRequiredActions().stream()
                    .map(RequiredActionProviderRepresentation::getProviderId),
                authentication.getUnregisteredRequiredActions().stream()
                    .map(RequiredActionProviderSimpleRepresentation::getProviderId))
            .collect(Collectors.toSet());

    Set<String> declared = new LinkedHashSet<>();
    for (AuthenticationManagementConfig.RequiredAction requiredAction : requiredActions) {
      String providerId = requiredAction.providerId();
      Validate.isTrue(
          declared.add(providerId),
          "Required action '%s' is configured more than once",
          providerId);
      Validate.isTrue(
          known.contains(providerId),
          "Required action '%s' is not a provider this Keycloak offers",
          providerId);
    }
  }

  private void registerUnregisteredActions(
      AuthenticationManagementResource authentication,
      List<AuthenticationManagementConfig.RequiredAction> requiredActions,
      Changes changes) {
    Map<String, RequiredActionProviderSimpleRepresentation> unregistered =
        authentication.getUnregisteredRequiredActions().stream()
            .collect(
                Collectors.toMap(
                    RequiredActionProviderSimpleRepresentation::getProviderId, r -> r));

    for (AuthenticationManagementConfig.RequiredAction requiredAction : requiredActions) {
      RequiredActionProviderSimpleRepresentation toRegister =
          unregistered.get(requiredAction.providerId());
      if (toRegister != null) {
        changes.added(Target.REQUIRED_ACTION, requiredAction.providerId());
        authentication.registerRequiredAction(toRegister);
      }
    }
  }

  private void updateRequiredActionIfChanged(
      AuthenticationManagementResource authentication,
      AuthenticationManagementConfig.RequiredAction requiredAction,
      int priority,
      Changes changes) {
    RequiredActionProviderRepresentation representation =
        authentication.getRequiredAction(requiredAction.providerId());
    String before = AbstractDiffer.toJson(representation);
    representation.setName(requiredAction.displayName());
    representation.setEnabled(requiredAction.enabled());
    representation.setDefaultAction(requiredAction.defaultAction());
    representation.setPriority(priority);
    String after = AbstractDiffer.toJson(representation);
    if (before.equals(after)) {
      return;
    }

    changes.updated(
        Target.REQUIRED_ACTION,
        requiredAction.providerId(),
        Differ.calculateMultilineDiff(before, after));
    authentication.updateRequiredAction(requiredAction.providerId(), representation);
  }
}
