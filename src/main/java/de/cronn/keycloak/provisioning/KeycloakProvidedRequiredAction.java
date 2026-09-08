package de.cronn.keycloak.provisioning;

public enum KeycloakProvidedRequiredAction {
  UPDATE_PASSWORD("UPDATE_PASSWORD", "Update Password"),
  TERMS_AND_CONDITIONS("TERMS_AND_CONDITIONS", "Terms and Conditions"),
  VERIFY_EMAIL("VERIFY_EMAIL", "Verify Email"),
  UPDATE_EMAIL("UPDATE_EMAIL", "Update Email"),
  VERIFY_PROFILE("VERIFY_PROFILE", "Verify Profile"),
  UPDATE_PROFILE("UPDATE_PROFILE", "Update Profile"),
  UPDATE_USER_LOCALE("update_user_locale", "Update User Locale"),
  DELETE_ACCOUNT("delete_account", "Delete Account"),
  DELETE_CREDENTIAL("delete_credential", "Delete Credential"),
  IDP_LINK("idp_link", "Linking Identity Provider"),
  CONFIGURE_TOTP("CONFIGURE_TOTP", "Configure OTP"),
  WEBAUTHN_REGISTER("webauthn-register", "Webauthn Register"),
  WEBAUTHN_REGISTER_PASSWORDLESS(
      "webauthn-register-passwordless", "Webauthn Register Passwordless"),
  CONFIGURE_RECOVERY_AUTHN_CODES("CONFIGURE_RECOVERY_AUTHN_CODES", "Recovery Authentication Codes"),
  ;

  private final String providerId;
  private final String displayName;

  KeycloakProvidedRequiredAction(String providerId, String displayName) {
    this.providerId = providerId;
    this.displayName = displayName;
  }

  public String getProviderId() {
    return providerId;
  }

  public String getDisplayName() {
    return displayName;
  }
}
