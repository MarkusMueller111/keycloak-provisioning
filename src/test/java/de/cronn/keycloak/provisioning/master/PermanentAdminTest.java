package de.cronn.keycloak.provisioning.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

class PermanentAdminTest {

  @Test
  void acceptsALowerCaseUsername() {
    PermanentAdmin.Standard admin = new PermanentAdmin.Standard("admin.user", "pw", null);

    assertThat(admin.buildUser().getUsername()).isEqualTo("admin.user");
  }

  /** Keycloak would store a different name than the one configured, so fail before it can. */
  @Test
  void rejectsAMixedCaseUsername() {
    assertThatThrownBy(() -> new PermanentAdmin.Standard("Admin.User", "pw", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Admin.User")
        .hasMessageContaining("lower-case");
  }

  @Test
  void rejectsABlankUsername() {
    assertThatThrownBy(() -> new PermanentAdmin.Standard(" ", "pw", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  /** The escape hatch takes the same create-once path, so it gets the same check. */
  @Test
  void rejectsAMixedCaseUsernameOnCustom() {
    UserRepresentation user = new UserRepresentation();
    user.setUsername("Admin.User");

    assertThatThrownBy(() -> new PermanentAdmin.Custom(user, userResource -> {}).buildUser())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Admin.User");
  }

  @Test
  void acceptsALowerCaseUsernameOnCustom() {
    UserRepresentation user = new UserRepresentation();
    user.setUsername("admin.user");

    assertThat(new PermanentAdmin.Custom(user, userResource -> {}).buildUser().getUsername())
        .isEqualTo("admin.user");
  }

  /** Digits and punctuation are not letters, so they are unaffected by the case rule. */
  @Test
  void acceptsAUsernameWithoutLetters() {
    assertThat(new PermanentAdmin.Standard("admin-2.0_x", "pw", null).buildUser().getUsername())
        .isEqualTo("admin-2.0_x");
  }
}
