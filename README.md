[![CI](https://github.com/cronn/keycloak-provisioning/actions/workflows/gradle.yml/badge.svg)](https://github.com/cronn/keycloak-provisioning/actions)
[![Valid Gradle Wrapper](https://github.com/cronn/keycloak-provisioning/workflows/Validate%20Gradle%20Wrapper/badge.svg)](https://github.com/cronn/keycloak-provisioning/actions/workflows/gradle-wrapper-validation.yml)
[![Maven Central](https://img.shields.io/maven-central/v/de.cronn/keycloak-provisioning?logo=apache%20maven)](https://search.maven.org/#search|ga|1|g:de.cronn%20AND%20keycloak-provisioning)
[![Apache 2.0](https://img.shields.io/github/license/cronn/keycloak-provisioning.svg)](http://www.apache.org/licenses/LICENSE-2.0)

# cronn keycloak-provisioning

Provisions a Keycloak realm from code instead of by hand: clients, roles, groups, authentication
flows, the user profile, and the hardening of the master realm.

Configuration clicked together in the admin console is invisible to review, easy to forget when a
second environment is set up, and impossible to diff. This library moves that configuration into
your application, where it is versioned with the rest of your code and applied on every start.

Three things follow from that:

- **Your realm configuration becomes code.** Client ids, role names and group names live in your
  own constants and enums instead of in a web form, so your IDE can rename them and find their
  usages, and the compiler keeps them in step with the code that authorizes against them.
- **It converges instead of accumulating.** The reconciling methods take the full desired state and
  remove what you no longer declare, so a realm that drifted — a client added by hand, a flow left
  over from an experiment — is brought back in line on the next run rather than collecting residue.
- **Secure defaults, not blank ones.** `MasterRealmProvisioner` starts from a hardened master realm
  — password policy, short sessions, brute-force protection, security headers, no standing
  temporary admins — rather than leaving each of those for you to remember.

## Usage

Add the following dependency to your project:

Gradle:
```groovy
implementation "de.cronn:keycloak-provisioning:{version}"
```

Maven:
```xml
<dependency>
    <groupId>de.cronn</groupId>
    <artifactId>keycloak-provisioning</artifactId>
    <version>{version}</version>
</dependency>
```

## Versioning

| Library Version | Keycloak Server | Java |
|-----------------|-----------------|------|
| 0.x             | 26.x            | 21+  |

Tested against 26.7.3. From `1.0.0` onwards the major version tracks the supported Keycloak major.
While `0.x` lasts, a minor release may still break the API.

The bundled `keycloak-admin-client` is an `api` dependency, so you will see it on your classpath at
version 26.0.12 even against a 26.7 server. That mismatch is expected: since 26.0.0 the client
libraries release independently and are supported "with the same server version and a few previous
major server versions" ([release notes][keycloak-client-26]).

## Connecting

Every operation goes through a `RealmManager`, which you obtain from a connection:

```java
KeycloakDefaultConnectionConfig config =
    new KeycloakDefaultConnectionConfig(
        serverUrl,
        new KeycloakAdminCredentialsConfig(adminUsername, adminPassword),
        new KeycloakProvisioningClientConfig("keycloak-provisioning", clientSecret));

try (DefaultKeycloakConnectionManager connection = new DefaultKeycloakConnectionManager(config)) {
    RealmManager realmManager = connection.realmManager("master");
}
```

`DefaultKeycloakConnectionManager` authenticates as the provisioning client, creating it from the
admin credentials on first use. You do not have to prepare anything in the admin console
beforehand.

If you manage the `Keycloak` client yourself, construct a `RealmManager` directly:

```java
new RealmManager(keycloak, "master", SystemClientNaming.standard());
```

Every connection manager acquires a token in its constructor, so a misconfigured connection fails
where you open it rather than on some later call. Failures are reported as `KeycloakException` with
the underlying `jakarta.ws.rs` exception as cause.

## Reconciling

The methods below take the **full desired state**. Anything they manage that is missing from what
you pass is deleted, so repeated runs converge on your configuration — and an incomplete list is
destructive.

| Method | Deletes |
|---|---|
| `createOrUpdateClients` | every client whose id carries the configured prefix (`system-` by default) and is not in the list |
| `configureFlows` | every flow that is not one of Keycloak's built-ins and is not in the list — **including flows created by hand in the admin console** |
| `addOrUpdateExecutions` | every execution of the named flows that is not in the list |
| `reconcileDefaultRoleComposites` | every composite of the realm's default role not in the list |
| `configureUserProfile` | every user-profile attribute not declared and not a Keycloak built-in |

`disableClients` is the exception: it disables what you name and touches nothing else.

### Client naming

`createOrUpdateClients` only ever deletes clients carrying the configured prefix, which is what
keeps it away from clients you did not provision. A realm therefore has to keep the same prefix for
its whole lifetime: changing it orphans everything provisioned under the old prefix and pulls
unrelated clients into scope for deletion.

```java
SystemClientNaming.standard()      // "system-"
SystemClientNaming.custom("acme-")
```

The provisioning client is deliberately **not** named through this: its id carries no prefix, so it
stays outside the managed namespace and your own `createOrUpdateClients` call — which deletes every
managed client not in the list — does not delete the identity you are connected as.

A connection carries one naming and passes it to every `RealmManager` it creates, so the two cannot
disagree:

```java
SystemClientNaming naming = SystemClientNaming.custom("acme-");

new KeycloakDefaultConnectionConfig(
    serverUrl,
    adminCredentials,
    new KeycloakProvisioningClientConfig("keycloak-provisioning", clientSecret),
    naming);
```

## Hardening the master realm

`MasterRealmProvisioner` applies a set of defaults to the master realm that a production deployment
usually wants and that Keycloak does not give you out of the box:

```java
MasterRealmConfig config =
    MasterRealmConfig.builder(url, new PermanentAdmin.Standard(username, password, email))
        .smtp(smtp)
        .build();

new MasterRealmProvisioner(realmManager, config).provision();
```

The defaults include a password policy (`length(12) and notContainsUsername and upperCase(1) and
lowerCase(1) and digits(1) and specialChars(1) and maxAuthAge(0)`), a 5-minute session idle
timeout with a 1-hour maximum lifespan, brute-force protection, security headers, a 7-day
admin-event expiration, and a user profile that requires an email address. Every one of them is a
builder method away — override what does not fit and leave the rest.

### Permanent admin

`provision()` creates the realm's permanent administrator, the human account that takes over from
Keycloak's temporary one. It is a `builder(...)` argument rather than an option, so it cannot be
forgotten.

The account is created **once**. If a user with that username already exists it is left entirely
untouched: no password reset, no role changes. Changing the configuration later therefore has no
effect on an admin that already exists.

`PermanentAdmin.Standard` sets an initial password the user must change on first login. A
configuration that would disable the required action behind that prompt is rejected by `build()`.
Use `PermanentAdmin.Custom` for other credential types.

`PermanentAdmin.none()` skips the admin entirely. Together with the default
`deleteTemporaryAdminUsers(true)` that deliberately leaves the realm without administrative access,
for deployments that do not want a standing human admin — create one on demand with `kc.sh
bootstrap-admin user` when it is actually needed (see [Recovery](#recovery)).

### Temporary admin users

Keycloak's bootstrap admin and any account created with `kc.sh bootstrap-admin` are marked as
temporary. Their credentials are usually well known (often `admin` / `admin`), so they should not
outlive the initial setup.

`provision()` deletes every account marked that way as its last step, right after the permanent
admin was created. Opt out with `deleteTemporaryAdminUsers(false)`.

### admin-cli

`admin-cli` is disabled by default: it allows signing in with a username and password alone,
bypassing the browser flow and any 2FA configured there. Keep it enabled only if your provisioning
connection relies on it as a fallback:

```java
MasterRealmConfig.builder(url, permanentAdmin)
    .disableAdminCliClient(false) // default: true
    .build();
```

Disabling it does not lock you out of the admin console, which signs in through a different client.

### Recovery

If no administrative access is left, recreate a bootstrap admin from inside the Keycloak container:

```bash
kc.sh bootstrap-admin user --username <name>
```

The password has to satisfy the realm's password policy (see above). A non-compliant password makes
the command fail with a misleading message that does not mention the policy at all:

```
ERROR: Failed to start server in (nonserver) mode
```

Note that `notContainsUsername` rejects a password containing the username regardless of its
length or complexity. Once logged in, `admin-cli` can be re-enabled in the admin console.

## Test fixtures

The `testFixtures` artifact provides `KeycloakTestTraits`, a mixin interface whose `validate*`
methods assert a realm's configuration against [validation-file-assertions] files. This lets you
lock down what your provisioning code actually produces and catch unintended changes:

Gradle:
```groovy
testImplementation testFixtures("de.cronn:keycloak-provisioning:{version}")
```

```java
class MyRealmTest implements KeycloakTestTraits {

    @Override
    public String keycloakUrl() {
        return serverUrl;
    }

    @Test
    void realmIsProvisioned() {
        validateKeycloakConfiguration(realmManager);
    }
}
```

`keycloakUrl()` is the only method you have to implement: it normalizes the server URL out of the
validation files so they stay stable across environments. To add your own normalizers, override
`keycloakValidationNormalizer()` and combine with
`KeycloakTestTraits.super.keycloakValidationNormalizer()`.

Assertions are serialized with properties in alphabetical order. If you are migrating validation
files that were written with a different ordering, override `jsonMapper()` to keep your existing
files valid rather than re-accepting all of them.

`validateFlows` covers every authentication flow in the realm, including the ones Keycloak ships
itself, which makes for a large validation file before you add any of your own. Use
`validateCustomFlows` to assert only the flows you provisioned.

Every assertion runs through a `RealmManager` you supply, so the identity behind its connection has
to be able to authenticate against the realm as your configuration leaves it. One case is worth
knowing about in advance: the default user profile requires an email address for admins, and an
admin without one is rejected when connecting via `admin-cli`:

```
{"error":"invalid_grant","error_description":"Account is not fully set up"}
```

Connect via the provisioning client, via an admin that has an email address, or with a user profile
that does not require one.

## Requirements

- Java 21+
- Keycloak 26.x (tested against 26.7.3)

## Related Projects

- [validation-file-assertions]

[validation-file-assertions]: https://github.com/cronn/validation-file-assertions
[keycloak-client-26]: https://github.com/keycloak/keycloak-client/blob/main/docs/release-notes/26_0_0.adoc
