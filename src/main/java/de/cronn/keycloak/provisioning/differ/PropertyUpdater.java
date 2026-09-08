package de.cronn.keycloak.provisioning.differ;

public interface PropertyUpdater<T> {
  void update(T target, T source);
}
