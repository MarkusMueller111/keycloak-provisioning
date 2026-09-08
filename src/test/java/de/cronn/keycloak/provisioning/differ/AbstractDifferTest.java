package de.cronn.keycloak.provisioning.differ;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code target} is the existing state, {@code source} the desired one. */
class AbstractDifferTest {

  /** Mutable, because a PropertyUpdater updates the target in place. */
  static final class Element {
    private final String id;
    private String value;

    Element(String id, String value) {
      this.id = id;
      this.value = value;
    }

    public String getId() {
      return id;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  @Test
  void partitionsElementsByWhetherTheyAreOnlyInOneSide() {
    AbstractDiffer<Element> differ =
        differ(
            List.of(new Element("kept", "a"), new Element("gone", "b")),
            List.of(new Element("kept", "a"), new Element("new", "c")));

    assertThat(ids(differ.getElementsToAdd())).containsExactly("new");
    assertThat(ids(differ.getElementsToDelete())).containsExactly("gone");
    assertThat(ids(differ.getElementsUnmodified())).containsExactly("kept");
    assertThat(differ.getElementsToUpdate()).isEmpty();
  }

  @Test
  void countsAnElementAsUpdatedOnlyWhenTheUpdaterChangesIt() {
    AbstractDiffer<Element> differ =
        differ(List.of(new Element("a", "old")), List.of(new Element("a", "new")));

    assertThat(differ.getElementsToUpdate()).hasSize(1);
    assertThat(differ.getElementsToUpdate().getFirst().newState().getValue()).isEqualTo("new");
    assertThat(differ.getElementsUnmodified()).isEmpty();
  }

  @Test
  void countsAnElementAsUnmodifiedWhenTheUpdaterChangesNothing() {
    AbstractDiffer<Element> differ =
        differ(List.of(new Element("a", "same")), List.of(new Element("a", "same")));

    assertThat(differ.getElementsToUpdate()).isEmpty();
    assertThat(ids(differ.getElementsUnmodified())).containsExactly("a");
  }

  /** The updater decides what counts as a change; a difference it ignores is not one. */
  @Test
  void ignoresDifferencesTheUpdaterDoesNotCopy() {
    AbstractDiffer<Element> differ =
        new AbstractDiffer<>(
            List.of(new Element("a", "old")),
            List.of(new Element("a", "new")),
            (target, source) -> {},
            Element::getId);

    assertThat(differ.getElementsToUpdate()).isEmpty();
    assertThat(ids(differ.getElementsUnmodified())).containsExactly("a");
  }

  @Test
  void treatsAnEmptyTargetAsAllAdditions() {
    AbstractDiffer<Element> differ =
        differ(List.of(), List.of(new Element("a", "x"), new Element("b", "y")));

    assertThat(ids(differ.getElementsToAdd())).containsExactly("a", "b");
    assertThat(differ.getElementsToDelete()).isEmpty();
  }

  @Test
  void treatsAnEmptySourceAsAllDeletions() {
    AbstractDiffer<Element> differ =
        differ(List.of(new Element("a", "x"), new Element("b", "y")), List.of());

    assertThat(ids(differ.getElementsToDelete())).containsExactly("a", "b");
    assertThat(differ.getElementsToAdd()).isEmpty();
  }

  /** Two elements sharing an id cannot be told apart, so the diff refuses rather than guess. */
  @Test
  void rejectsDuplicateIdentities() {
    assertThatThrownBy(
            () -> differ(List.of(new Element("a", "x"), new Element("a", "y")), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate key 'a'");
  }

  @Test
  void keepsTheOrderOfTheListItWasGiven() {
    AbstractDiffer<Element> differ =
        differ(
            List.of(),
            List.of(new Element("z", "1"), new Element("m", "2"), new Element("a", "3")));

    assertThat(ids(differ.getElementsToAdd())).containsExactly("z", "m", "a");
  }

  private static AbstractDiffer<Element> differ(List<Element> target, List<Element> source) {
    return new AbstractDiffer<>(target, source, (t, s) -> t.setValue(s.getValue()), Element::getId);
  }

  private static List<String> ids(List<Element> elements) {
    return elements.stream().map(Element::getId).toList();
  }
}
