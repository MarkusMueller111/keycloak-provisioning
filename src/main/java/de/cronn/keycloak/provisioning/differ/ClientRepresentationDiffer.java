package de.cronn.keycloak.provisioning.differ;

import static de.cronn.keycloak.provisioning.differ.Declared.mergedMap;
import static de.cronn.keycloak.provisioning.differ.Declared.orDefault;
import static de.cronn.keycloak.provisioning.differ.Declared.orDefaultIgnoringOrder;
import static de.cronn.keycloak.provisioning.differ.Declared.orUnset;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.keycloak.representations.idm.ClientRepresentation;

public class ClientRepresentationDiffer extends AbstractDiffer<ClientRepresentation> {

  /** Keycloak sets this on every client it creates. */
  private static final Map<String, String> DEFAULT_ATTRIBUTES = Map.of("realm_client", "false");

  private static PropertyUpdater<ClientRepresentation> clientRepresentationUpdater() {
    return (target, source) -> {
      target.setClientId(source.getClientId());
      target.setName(orUnset(source.getName(), target.getName()));
      target.setDescription(orUnset(source.getDescription(), target.getDescription()));
      target.setPublicClient(orDefault(source.isPublicClient(), target.isPublicClient(), false));
      target.setRootUrl(orUnset(source.getRootUrl(), target.getRootUrl()));
      target.setBaseUrl(orUnset(source.getBaseUrl(), target.getBaseUrl()));
      target.setAdminUrl(orUnset(source.getAdminUrl(), target.getAdminUrl()));
      target.setDirectAccessGrantsEnabled(
          orDefault(
              source.isDirectAccessGrantsEnabled(), target.isDirectAccessGrantsEnabled(), false));
      target.setStandardFlowEnabled(
          orDefault(source.isStandardFlowEnabled(), target.isStandardFlowEnabled(), true));
      target.setImplicitFlowEnabled(
          orDefault(source.isImplicitFlowEnabled(), target.isImplicitFlowEnabled(), false));
      target.setServiceAccountsEnabled(
          orDefault(source.isServiceAccountsEnabled(), target.isServiceAccountsEnabled(), false));
      target.setAuthorizationServicesEnabled(
          orDefault(
              source.getAuthorizationServicesEnabled(),
              target.getAuthorizationServicesEnabled(),
              false));
      target.setBearerOnly(orDefault(source.isBearerOnly(), target.isBearerOnly(), false));
      target.setConsentRequired(
          orDefault(source.isConsentRequired(), target.isConsentRequired(), false));
      target.setFullScopeAllowed(
          orDefault(source.isFullScopeAllowed(), target.isFullScopeAllowed(), true));
      target.setClientAuthenticatorType(
          orDefault(
              source.getClientAuthenticatorType(),
              target.getClientAuthenticatorType(),
              "client-secret"));
      target.setAuthenticationFlowBindingOverrides(
          mergedMap(
              source.getAuthenticationFlowBindingOverrides(),
              target.getAuthenticationFlowBindingOverrides(),
              Map.of(),
              key -> false,
              key -> ""));
      target.setProtocol(orDefault(source.getProtocol(), target.getProtocol(), "openid-connect"));
      target.setAlwaysDisplayInConsole(
          orDefault(source.isAlwaysDisplayInConsole(), target.isAlwaysDisplayInConsole(), false));
      target.setSurrogateAuthRequired(
          orDefault(source.isSurrogateAuthRequired(), target.isSurrogateAuthRequired(), false));
      target.setNodeReRegistrationTimeout(
          orDefault(
              source.getNodeReRegistrationTimeout(), target.getNodeReRegistrationTimeout(), -1));
      target.setType(orUnset(source.getType(), target.getType()));
      target.setFrontchannelLogout(
          orDefault(source.isFrontchannelLogout(), target.isFrontchannelLogout(), false));
      target.setRedirectUris(
          orDefaultIgnoringOrder(source.getRedirectUris(), target.getRedirectUris(), List.of()));
      target.setWebOrigins(
          orDefaultIgnoringOrder(
              source.getWebOrigins(), target.getWebOrigins(), originsOf(source.getRedirectUris())));
      if (source.getSecret() != null) {
        // A null secret means "not declared", not "remove it".
        target.setSecret(source.getSecret());
      }
      // Keycloak drops an attribute sent as "", but keeps one that is left out.
      target.setAttributes(
          mergedMap(
              source.getAttributes(),
              target.getAttributes(),
              DEFAULT_ATTRIBUTES,
              key -> key.startsWith("client.secret."),
              key -> ""));
      target.setEnabled(orDefault(source.isEnabled(), target.isEnabled(), true));
      // Scope assignments are left out on purpose: Keycloak ignores them on the client update,
      // so RealmManager provisions them through the dedicated endpoints afterwards.
      // authorizationSettings is left out as well: an object graph with ids of its own, it needs a
      // pass of its own like the client scopes.
      // So are protocol mappers: Keycloak returns them with ids and in its own order, so
      // RealmManager provisions them by name afterwards. authorizationSettings is not modelled.
    };
  }

  /** What Keycloak derives the web origins from when a client is created without any. */
  private static List<String> originsOf(List<String> redirectUris) {
    if (redirectUris == null) {
      return List.of();
    }
    return redirectUris.stream()
        .filter(uri -> uri.startsWith("http"))
        .map(URI::create)
        .map(
            uri ->
                uri.getScheme()
                    + "://"
                    + uri.getHost()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort()))
        .distinct()
        .toList();
  }

  public ClientRepresentationDiffer(
      List<ClientRepresentation> target, List<ClientRepresentation> source) {
    super(target, source, clientRepresentationUpdater(), ClientRepresentation::getClientId);
  }
}
