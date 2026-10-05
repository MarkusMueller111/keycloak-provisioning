package de.cronn.keycloak.provisioning.differ;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.cronn.commons.lang.StreamUtil;
import de.cronn.keycloak.provisioning.KeycloakException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public class AbstractDiffer<T> {
  private final List<T> target;
  private final List<T> source;
  private final PropertyUpdater<T> updater;
  private final Function<? super T, String> id;
  private List<T> elementsToDelete;
  private List<T> elementsToAdd;
  private final List<ToUpdate<T>> elementsToUpdate = new ArrayList<>();
  private final List<T> elementsUnmodified = new ArrayList<>();

  public AbstractDiffer(
      List<T> target, List<T> source, PropertyUpdater<T> updater, Function<? super T, String> id) {

    this.target = target;
    this.source = source;
    this.updater = updater;
    this.id = id;

    calculateChanges();
  }

  public AbstractDiffer(List<T> target, List<T> source) {
    this(target, source, (a, b) -> {}, T::toString);
  }

  public List<T> getElementsToAdd() {
    return elementsToAdd;
  }

  public List<T> getElementsToDelete() {
    return elementsToDelete;
  }

  public List<ToUpdate<T>> getElementsToUpdate() {
    return elementsToUpdate;
  }

  public List<T> getElementsUnmodified() {
    return elementsUnmodified;
  }

  private void calculateChanges() {
    Map<String, T> targetMap = mapById(target);
    Map<String, T> sourceMap = mapById(source);

    calculateElementsToDelete(targetMap, sourceMap);
    calculateElementsToAdd(targetMap, sourceMap);
    calculateElementsToUpdate(targetMap, sourceMap);
  }

  private void calculateElementsToUpdate(Map<String, T> targetMap, Map<String, T> sourceMap) {
    Set<String> inBoth = intersectKeys(targetMap, sourceMap);

    for (String key : inBoth) {
      addUpdateElementIfDiff(targetMap.get(key), sourceMap.get(key));
    }
  }

  private void addUpdateElementIfDiff(T targetElement, T sourceElement) {
    String oldStateAsJson = toJson(targetElement);
    updater.update(targetElement, sourceElement);
    String newStateAsJson = toJson(targetElement);

    if (!Objects.equals(oldStateAsJson, newStateAsJson)) {
      String diff = Differ.calculateMultilineDiff(oldStateAsJson, newStateAsJson);
      elementsToUpdate.add(new ToUpdate<>(targetElement, diff));
    } else {
      elementsUnmodified.add(targetElement);
    }
  }

  private static <T> Set<String> intersectKeys(Map<String, T> targetMap, Map<String, T> sourceMap) {
    Set<String> inBoth = new LinkedHashSet<>(targetMap.keySet());
    inBoth.retainAll(sourceMap.keySet());
    return inBoth;
  }

  /** A detached copy, so a caller's representation is never written to. */
  public static <T> T copyOf(T value, Class<T> type) {
    try {
      return objectMapper().readValue(objectMapper().writeValueAsString(value), type);
    } catch (JsonProcessingException e) {
      throw new KeycloakException("JSON copy failed", e);
    }
  }

  private static ObjectMapper objectMapper() {
    return JsonMapper.builder()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(SerializationFeature.INDENT_OUTPUT, true)
        // Jackson 3's default; set explicitly so diffs survive the move back to it.
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .build();
  }

  public static String toJson(Object value) {
    try {
      return objectMapper().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new KeycloakException("JSON serialization failed", e);
    }
  }

  private void calculateElementsToAdd(Map<String, T> targetMap, Map<String, T> sourceMap) {
    Set<String> onlyInSource = leftMinusRightKeys(sourceMap, targetMap);
    elementsToAdd = onlyInSource.stream().map(sourceMap::get).toList();
  }

  private static <T> Set<String> leftMinusRightKeys(Map<String, T> left, Map<String, T> right) {
    Set<String> leftKeys = new LinkedHashSet<>(left.keySet());
    leftKeys.removeAll(right.keySet());
    return leftKeys;
  }

  private void calculateElementsToDelete(Map<String, T> targetMap, Map<String, T> sourceMap) {
    Set<String> onlyInTarget = leftMinusRightKeys(targetMap, sourceMap);
    elementsToDelete = onlyInTarget.stream().map(targetMap::get).toList();
  }

  private Map<String, T> mapById(List<T> list) {
    return list.stream().collect(StreamUtil.toLinkedHashMap(id, person -> person));
  }
}
