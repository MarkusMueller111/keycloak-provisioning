package de.cronn.keycloak.provisioning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.keycloak.representations.idm.RealmRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum SecurityHeader {
  CONTENT_SECURITY_POLICY("contentSecurityPolicy"),
  CONTENT_SECURITY_POLICY_REPORT_ONLY("contentSecurityPolicyReportOnly"),
  REFERRER_POLICY("referrerPolicy"),
  STRICT_TRANSPORT_SECURITY("strictTransportSecurity"),
  X_CONTENT_TYPE_OPTIONS("xContentTypeOptions"),
  X_FRAME_OPTIONS("xFrameOptions"),
  X_ROBOTS_TAG("xRobotsTag"),
  ;

  private static final Logger log = LoggerFactory.getLogger(SecurityHeader.class);

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

  /** Writes {@code headers} into the representation, leaving any other header Keycloak set. */
  public static void applyTo(
      RealmRepresentation realmRepresentation, Map<SecurityHeader, String> headers) {
    Map<String, String> browserSecurityHeaders = realmRepresentation.getBrowserSecurityHeaders();
    if (browserSecurityHeaders == null) {
      browserSecurityHeaders = new LinkedHashMap<>();
    }

    for (Map.Entry<SecurityHeader, String> header : headers.entrySet()) {
      String newValue = header.getValue();
      String oldValue = browserSecurityHeaders.put(header.getKey().getKeycloakName(), newValue);
      if (!Objects.equals(oldValue, newValue)) {
        log.info(
            "Replaced default security header {}\n-    {}\n+    {}",
            header.getKey().name(),
            oldValue,
            newValue);
      }
    }

    realmRepresentation.setBrowserSecurityHeaders(browserSecurityHeaders);
  }

  public String getKeycloakName() {
    return keycloakName;
  }
}
