package de.cronn.keycloak.provisioning.differ;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Differ {

  /**
   * Diffs are logged at INFO, so this covers what a diffed representation can carry verbatim. Not
   * {@code value}: only {@link org.keycloak.representations.idm.CredentialRepresentation} has one,
   * and credentials are left out of the diff on purpose, while a {@code value} key does occur in
   * user-profile validator config, which is worth reading in a diff.
   */
  private static final List<String> MASKED_JSON_KEYS =
      List.of("password", "secret", "secretData", "credentialData", "privateKey");

  private static final Pattern JSON_KEY = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:");

  private Differ() {
    throw new IllegalStateException("Utility class");
  }

  public static String calculateMultilineDiff(String oldString, String newString) {
    List<String> oldLines = splitLines(oldString);
    List<String> newLines = splitLines(newString);

    Patch<String> diff = DiffUtils.diff(oldLines, newLines);

    return diff.getDeltas().stream().map(Differ::renderAsDiff).collect(Collectors.joining("\n\n"));
  }

  private static String renderAsDiff(AbstractDelta<String> delta) {
    List<String> sourceLines = delta.getSource().getLines();
    List<String> targetLines = delta.getTarget().getLines();

    List<String> diffLines = new ArrayList<>();
    for (int i = 0; i < Math.max(sourceLines.size(), targetLines.size()); i++) {
      if (sourceLines.size() > i) {
        diffLines.add("- " + maskSensitiveData(sourceLines.get(i)));
      }
      if (targetLines.size() > i) {
        diffLines.add("+ " + maskSensitiveData(targetLines.get(i)));
      }
    }
    return String.join("\n", diffLines);
  }

  private static List<String> splitLines(String oldString) {
    return Arrays.stream(oldString.split("\n")).toList();
  }

  /**
   * A client attribute carries its secret under a dotted key of its own, so the whole key is no
   * help: {@code my.api.secret} has to mask. Matching the end of a segment rather than any part of
   * it keeps {@code passwordPolicy} readable.
   */
  private static String maskSensitiveData(String line) {
    Matcher matcher = JSON_KEY.matcher(line);
    if (!matcher.find()) {
      return line;
    }
    return isSensitive(matcher.group(1))
        ? "\"%s\" : \"[masked]\"".formatted(matcher.group(1))
        : line;
  }

  private static boolean isSensitive(String key) {
    String[] segments = key.split("[.\\-_]");
    return Stream.concat(
            Arrays.stream(segments),
            // saml.signing.private.key splits into segments that are harmless on their own.
            Stream.of(String.join("", segments)))
        .map(segment -> segment.toLowerCase(Locale.ROOT))
        .anyMatch(
            segment ->
                MASKED_JSON_KEYS.stream()
                    .map(maskedKey -> maskedKey.toLowerCase(Locale.ROOT))
                    .anyMatch(segment::endsWith));
  }
}
