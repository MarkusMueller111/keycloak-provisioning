package de.cronn.keycloak.provisioning.differ;

import java.util.List;
import org.keycloak.representations.idm.ClientRepresentation;

public class ClientRepresentationDiffer extends AbstractDiffer<ClientRepresentation> {

  /** As {@link Differ} renders a changed secret, whose value it masks rather than printing. */
  private static final String MASKED_SECRET_LINE = "\"secret\" : \"[masked]\"";

  /**
   * Keycloak ignores a new secret on the client update, so it has to be recreated instead. Matching
   * the whole rendered line rather than the word: a diff mentions {@code secret} for unrelated
   * reasons too, such as Keycloak's own {@code client.secret.creation.time} attribute disappearing,
   * and a client should not be torn down over that.
   */
  public static boolean isSecretChanged(String multiLineDiff) {
    return multiLineDiff
        .lines()
        .map(line -> line.replaceFirst("^[-+] ", "").trim())
        .anyMatch(MASKED_SECRET_LINE::equals);
  }

  private static PropertyUpdater<ClientRepresentation> clientRepresentationUpdater() {
    return (target, source) -> {
      target.setClientId(source.getClientId());
      target.setName(source.getName());
      target.setDescription(source.getDescription());
      target.setPublicClient(source.isPublicClient());
      target.setRootUrl(source.getRootUrl());
      target.setBaseUrl(source.getBaseUrl());
      target.setAdminUrl(source.getAdminUrl());
      target.setDirectAccessGrantsEnabled(source.isDirectAccessGrantsEnabled());
      target.setFrontchannelLogout(source.isFrontchannelLogout());
      target.setRedirectUris(source.getRedirectUris());
      target.setWebOrigins(source.getWebOrigins());
      target.setSecret(source.getSecret());
      target.setAttributes(source.getAttributes());
      // Scope assignments are left out on purpose: Keycloak ignores them on the client update,
      // so RealmManager reconciles them through the dedicated endpoints afterwards.
    };
  }

  public ClientRepresentationDiffer(
      List<ClientRepresentation> target, List<ClientRepresentation> source) {
    super(target, source, clientRepresentationUpdater(), ClientRepresentation::getClientId);
  }
}
