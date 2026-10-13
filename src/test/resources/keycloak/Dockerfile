# Pre-runs the Quarkus augmentation that start-dev otherwise repeats on every boot, which is about
# half of the container's startup time. dev-mem keeps realm data in memory: every test either drops
# its realm or throws the whole container away.
FROM quay.io/keycloak/keycloak:26.8.1 AS builder
ENV KC_HEALTH_ENABLED=true
ENV KC_DB=dev-mem
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
