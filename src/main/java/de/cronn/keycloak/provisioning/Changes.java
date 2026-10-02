package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.Change.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Collects the changes of one provisioning call and logs each one as it is recorded, so the log and
 * the {@link ProvisioningResult} cannot disagree. Record a change right before sending its write.
 */
public final class Changes {

  private final Logger log;
  private final String realmName;
  private final List<Change> changes = new ArrayList<>();

  public Changes(Logger log, String realmName) {
    this.log = Objects.requireNonNull(log);
    this.realmName = Objects.requireNonNull(realmName);
  }

  public void added(Target target, String name) {
    added(target, name, null);
  }

  public void added(Target target, String name, String parent) {
    record(new Change(target, name, parent, Type.ADDED, null), "Adding", "to");
  }

  public void updated(Target target, String name, String diff) {
    updated(target, name, null, diff);
  }

  public void updated(Target target, String name, String parent, String diff) {
    record(new Change(target, name, parent, Type.UPDATED, diff), "Updating", "of");
  }

  public void removed(Target target, String name) {
    removed(target, name, null);
  }

  public void removed(Target target, String name, String parent) {
    record(new Change(target, name, parent, Type.REMOVED, null), "Removing", "from");
  }

  public ProvisioningResult result() {
    return new ProvisioningResult(changes);
  }

  private void record(Change change, String verb, String preposition) {
    changes.add(change);
    StringBuilder message =
        new StringBuilder(verb)
            .append(' ')
            .append(change.target().label())
            .append(" '")
            .append(change.name())
            .append('\'');
    if (change.parent() != null) {
      message.append(' ').append(preposition).append(" '").append(change.parent()).append('\'');
    }
    if (change.target() != Target.REALM) {
      message.append(" in realm '").append(realmName).append('\'');
    }
    if (change.diff() != null) {
      message.append(":\n").append(change.diff());
    }
    log.info("{}", message);
  }
}
