package de.cronn.keycloak.provisioning.differ;

public record ToUpdate<T>(T newState, String multiLineDiff) {}
