package de.cronn.keycloak.provisioning.differ;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class Differ {

  private static final List<String> MASKED_JSON_KEYS = List.of("password", "secret");

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

  private static String maskSensitiveData(String line) {
    for (String maskedKey : MASKED_JSON_KEYS) {
      String quotedMaskedKey = "\"%s\"".formatted(maskedKey);
      if (line.trim().startsWith(quotedMaskedKey)) {
        return quotedMaskedKey + " : \"[masked]\"";
      }
    }
    return line;
  }
}
