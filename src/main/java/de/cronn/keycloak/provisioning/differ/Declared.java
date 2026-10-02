package de.cronn.keycloak.provisioning.differ;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Resolves a declared value against the one Keycloak holds. An undeclared value means Keycloak's
 * default, so removing a declaration restores that default rather than keeping the last value.
 *
 * <p>Each method returns {@code current} when it is already equivalent to the desired value, so a
 * representation that needs no update serializes exactly as before. Keycloak ignores a {@code null}
 * on update, which is why a default has to be sent as an explicit value.
 */
public final class Declared {

  private Declared() {}

  /** {@code null} on either side stands for {@code keycloakDefault}. */
  public static <V> V orDefault(V declared, V current, V keycloakDefault) {
    V desired = declared != null ? declared : keycloakDefault;
    V effectiveCurrent = current != null ? current : keycloakDefault;
    return Objects.equals(desired, effectiveCurrent) ? current : desired;
  }

  /** Keycloak clears a string on update only when sent {@code ""}. */
  public static String orUnset(String declared, String current) {
    return orDefault(declared, current, "");
  }

  /** Keycloak stores these lists as sets and returns them in an order of its own. */
  public static List<String> orDefaultIgnoringOrder(
      List<String> declared, List<String> current, List<String> keycloakDefault) {
    List<String> desired = declared != null ? declared : keycloakDefault;
    List<String> effectiveCurrent = current != null ? current : keycloakDefault;
    return Set.copyOf(desired).equals(Set.copyOf(effectiveCurrent)) ? current : desired;
  }

  /**
   * For maps Keycloak merges into on update, where a key left out keeps its value: a key that is no
   * longer declared is sent with {@code resetValue} instead.
   *
   * @param keycloakOwned keys Keycloak maintains itself, which are never reset
   */
  public static Map<String, String> mergedMap(
      Map<String, String> declared,
      Map<String, String> current,
      Map<String, String> keycloakDefaults,
      Predicate<String> keycloakOwned,
      UnaryOperator<String> resetValue) {
    Map<String, String> currentOrEmpty = current != null ? current : Map.of();
    Map<String, String> desired = new LinkedHashMap<>(keycloakDefaults);
    if (declared != null) {
      desired.putAll(declared);
    }

    Map<String, String> result = new LinkedHashMap<>(currentOrEmpty);
    result.putAll(desired);
    for (String key : currentOrEmpty.keySet()) {
      if (!desired.containsKey(key) && !keycloakOwned.test(key)) {
        result.put(key, resetValue.apply(key));
      }
    }
    return result.equals(currentOrEmpty) ? current : result;
  }
}
