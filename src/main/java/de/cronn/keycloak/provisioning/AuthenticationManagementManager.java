package de.cronn.keycloak.provisioning;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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

  public void configure(AuthenticationManagementConfig config) {
    AuthenticationManagementResource authentication = realm.flows();

    List<AuthenticationManagementConfig.RequiredAction> requiredActions = config.requiredActions();
    if (!requiredActions.isEmpty()) {
      registerUnregisteredActions(authentication, requiredActions);

      Map<String, RequiredActionProviderRepresentation> existingByProviderId =
          authentication.getRequiredActions().stream()
              .collect(
                  Collectors.toMap(RequiredActionProviderRepresentation::getProviderId, r -> r));

      int priority = 10;
      for (AuthenticationManagementConfig.RequiredAction requiredAction : requiredActions) {
        upsertRequiredAction(authentication, requiredAction, priority);
        priority += 10;
        existingByProviderId.remove(requiredAction.providerId());
      }

      for (RequiredActionProviderRepresentation unmanaged : existingByProviderId.values()) {
        removeRegisteredAction(authentication, unmanaged);
      }
    }
  }

  private void registerUnregisteredActions(
      AuthenticationManagementResource authentication,
      List<AuthenticationManagementConfig.RequiredAction> requiredActions) {
    Map<String, RequiredActionProviderSimpleRepresentation> unregistered =
        authentication.getUnregisteredRequiredActions().stream()
            .collect(
                Collectors.toMap(
                    RequiredActionProviderSimpleRepresentation::getProviderId, r -> r));

    for (AuthenticationManagementConfig.RequiredAction requiredAction : requiredActions) {
      RequiredActionProviderSimpleRepresentation toRegister =
          unregistered.get(requiredAction.providerId());
      if (toRegister != null) {
        log.info("Registering required action provider '{}'", requiredAction.providerId());
        authentication.registerRequiredAction(toRegister);
      }
    }
  }

  private void upsertRequiredAction(
      AuthenticationManagementResource authentication,
      AuthenticationManagementConfig.RequiredAction requiredAction,
      int priority) {
    RequiredActionProviderRepresentation representation =
        authentication.getRequiredAction(requiredAction.providerId());
    representation.setName(requiredAction.displayName());
    representation.setEnabled(requiredAction.enabled());
    representation.setDefaultAction(requiredAction.defaultAction());
    representation.setPriority(priority);

    log.info(
        "Configuring required action '{}' (enabled={}, defaultAction={}, priority={})",
        requiredAction.providerId(),
        requiredAction.enabled(),
        requiredAction.defaultAction(),
        priority);
    authentication.updateRequiredAction(requiredAction.providerId(), representation);
  }

  private void removeRegisteredAction(
      AuthenticationManagementResource authentication,
      RequiredActionProviderRepresentation action) {

    log.info("Removing required action '{}'", action.getAlias());
    authentication.removeRequiredAction(action.getAlias());
  }
}
