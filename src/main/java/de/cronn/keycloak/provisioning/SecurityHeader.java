package de.cronn.keycloak.provisioning;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.keycloak.representations.idm.RealmRepresentation;

public enum SecurityHeader {
  CONTENT_SECURITY_POLICY("contentSecurityPolicy"),
  CONTENT_SECURITY_POLICY_REPORT_ONLY("contentSecurityPolicyReportOnly"),
  REFERRER_POLICY("referrerPolicy"),
  STRICT_TRANSPORT_SECURITY("strictTransportSecurity"),
  X_CONTENT_TYPE_OPTIONS("xContentTypeOptions"),
  X_FRAME_OPTIONS("xFrameOptions"),
  X_ROBOTS_TAG("xRobotsTag"),
  ;

  public static final Map<SecurityHeader, String> DEFAULT_VALUES =
      Map.of(
          CONTENT_SECURITY_POLICY,
              "default-src 'self'; script-src 'self' 'unsafe-inline'; frame-src 'self'; frame-ancestors 'self'; object-src 'none'; base-uri 'self';",
          CONTENT_SECURITY_POLICY_REPORT_ONLY, "",
          REFERRER_POLICY, "no-referrer",
          STRICT_TRANSPORT_SECURITY, "max-age=31536000; includeSubDomains",
          X_CONTENT_TYPE_OPTIONS, "nosniff",
          X_FRAME_OPTIONS, "SAMEORIGIN",
          X_ROBOTS_TAG, "none");

  SecurityHeader(String keycloakName) {
    this.keycloakName = keycloakName;
  }

  private final String keycloakName;

  /**
   * Every header gets a value, so one that is no longer declared returns to its default rather than
   * keeping whatever it was last set to.
   */
  public static Map<SecurityHeader, String> mergeOverDefaults(
      Map<SecurityHeader, String> declared) {
    Map<SecurityHeader, String> merged = new EnumMap<>(DEFAULT_VALUES);
    if (declared != null) {
      merged.putAll(declared);
    }
    return Collections.unmodifiableMap(merged);
  }

  /** Writes {@code headers} into the representation, leaving any other header Keycloak set. */
  public static void applyTo(
      RealmRepresentation realmRepresentation, Map<SecurityHeader, String> headers) {
    Map<String, String> current = realmRepresentation.getBrowserSecurityHeaders();
    Map<String, String> browserSecurityHeaders = current == null ? new LinkedHashMap<>() : current;

    // The realm diff reports what changed, so this does not log as well.
    headers.forEach((header, value) -> browserSecurityHeaders.put(header.getKeycloakName(), value));

    realmRepresentation.setBrowserSecurityHeaders(browserSecurityHeaders);
  }

  public String getKeycloakName() {
    return keycloakName;
  }
}
