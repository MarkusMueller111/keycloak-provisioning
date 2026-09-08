package de.cronn.keycloak.provisioning;

import java.util.Map;

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

  public String getKeycloakName() {
    return keycloakName;
  }
}
