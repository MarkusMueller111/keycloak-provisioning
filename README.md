[![CI](https://github.com/cronn/keycloak-provisioning/actions/workflows/gradle.yml/badge.svg)](https://github.com/cronn/keycloak-provisioning/actions)
[![Valid Gradle Wrapper](https://github.com/cronn/keycloak-provisioning/workflows/Validate%20Gradle%20Wrapper/badge.svg)](https://github.com/cronn/keycloak-provisioning/actions/workflows/gradle-wrapper-validation.yml)
[![Maven Central](https://img.shields.io/maven-central/v/de.cronn/keycloak-provisioning?logo=apache%20maven)](https://search.maven.org/#search|ga|1|g:de.cronn%20AND%20keycloak-provisioning)
[![Apache 2.0](https://img.shields.io/github/license/cronn/keycloak-provisioning.svg)](http://www.apache.org/licenses/LICENSE-2.0)

# cronn keycloak-provisioning

Provisions Keycloak realms from typed Java instead of by hand: realm settings, clients, client scopes,
authentication flows, the user profile, roles, groups etc.

Configuration clicked together in the admin console is invisible to review, easy to forget when a
second environment is set up, and impossible to diff. This library moves it into your application,
where it is versioned with the rest of your code and applied on every start.

- **Realm configuration becomes code.** Client ids and scope names live in your own constants, so
  your IDE renames them and the compiler keeps them in step with the code that authorizes against
  them.
- **It converges instead of accumulating.** Each run applies the full desired state, so a realm
  that drifted — a client added by hand, a flow left over from an experiment — is brought back in
  line rather than collecting residue.
- **Secure defaults, not blank ones.** A hardened realm out of the box: password policy,
  short sessions, brute-force protection, security headers, no standing temporary admins.

## 📦 Usage

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

## 🔢 Versioning

| Library Version | Keycloak Server | Java |
|-----------------|-----------------|------|
| 0.x             | 26.x            | 21+  |

Tested against 26.7.3. From `1.0.0` onwards the major version tracks the supported Keycloak major.
While `0.x` lasts, a minor release may still break the API.

The bundled `keycloak-admin-client` is an `api` dependency, so you will see it on your classpath at
version 26.0.12 even against a 26.7 server. That mismatch is expected: since 26.0.0 the client
libraries release independently and are supported "with the same server version and a few previous
major server versions" ([release notes][keycloak-client-26]).

## 🚀 Getting started

**1. Connect.** The provisioning client is created from the bootstrap admin on first use, so
nothing has to be prepared in the admin console.

```java
KeycloakDefaultConnectionConfig config =
    new KeycloakDefaultConnectionConfig(
        serverUrl,
        // the server's KC_BOOTSTRAP_ADMIN_USERNAME / KC_BOOTSTRAP_ADMIN_PASSWORD
        new KeycloakAdminCredentialsConfig("admin", "admin"),
        // created on first use; pick a secret and keep it
        new KeycloakProvisioningClientConfig("keycloak-provisioning", clientSecret));

// AutoCloseable: use try-with-resources around steps 2 and 3
KeycloakConnectionManager connection = new DefaultKeycloakConnectionManager(config);
```

**2. Harden the master realm.** Applies every default — password policy, short sessions,
brute-force protection, security headers — and creates an admin to replace the bootstrap one.

```java
MasterRealmConfig masterConfig =
    MasterRealmConfig.builder(
            serverUrl,
            new PermanentAdmin.Standard("admin-user", password, "admin@example.com"))
        .build();

new MasterRealmProvisioner(connection.realmManager("master"), masterConfig).provision();
```

> [!CAUTION]
> This deletes Keycloak's temporary admin users once the permanent one exists, so the bootstrap
> credentials stop working. Keep the permanent admin's password, or pass
> `deleteTemporaryAdminUsers(false)` while you are trying things out.

**3. Provision your realm.** Create it, then put your clients in it.

```java
RealmManager realmManager = connection.realmManager("my-realm");
new AppRealmProvisioner(realmManager, AppRealmConfig.builder("my-realm").build()).provision();

ClientRepresentation client = new ClientRepresentation();
client.setClientId(ManagedNaming.standard().clientId("my-app").value());   // "system-my-app"
client.setEnabled(true);

realmManager.provisionManagedClients(List.of(client));
```

Run it twice: the second run changes nothing. Add a client and re-run, and it appears; drop one
from the list and re-run, and it is deleted — that is the whole model.

> [!WARNING]
> `provisionManagedClients` takes the **full** list of clients you manage, not a list of additions.
> The same goes for every other `provision*` method. See [Provisioning](#provisioning) for what each
> one resets.

## 🔌 Connecting

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
new RealmManager(keycloak, "master", ManagedNaming.standard());
```

Every connection manager acquires a token in its constructor, so a misconfigured connection fails
where you open it rather than on some later call. Failures are reported as `KeycloakException` with
the underlying `jakarta.ws.rs` exception as cause.

## 🏛️ Provisioning an application realm

`AppRealmProvisioner` creates a realm and keeps its realm-level settings in sync:

```java
AppRealmConfig config =
    AppRealmConfig.builder("my-realm")
        .displayName("My Application")   // defaults to the realm name
        .sslRequired(SslRequired.NONE)   // default: EXTERNAL, as in Keycloak
        .sessionIdleTimeout(Duration.ofMinutes(30))
        .build();

new AppRealmProvisioner(realmManager, config).provision();
```

It hardens what Keycloak leaves open rather than copying its defaults: the same password policy and
session limits as the master realm, brute-force protection, login events kept for 90 days and admin
events for 7, security headers, and the same treatment of Keycloak's built-in clients —
`admin-cli` and `broker` disabled. Anything not modelled (locales, login theme, SMTP, WebAuthn)
goes through `realmCustomizer`, which runs last and can override the rest.

> [!IMPORTANT]
> The session defaults log an end user out after 5 idle minutes, with a 1 hour cap. That suits an
> admin realm more than most applications — set `sessionIdleTimeout` and `sessionMaxLifespan` to
> what yours needs.

The realm's **content** is deliberately not part of the config: roles, groups, clients and scopes
differ per project, as does the order they have to be created in, so you provision them with
`RealmManager` calls afterwards.

**Provision the realm first.** Creating it refreshes the access token, which the admin client
issued before your realm existed and which does not cover it yet. Provisioning content into a realm
that does not exist fails with a bare `NotFoundException` from the admin client, which does not say
which realm is missing. Two more orderings are forced by Keycloak: roles before anything
referencing them by name, and client scopes before the clients that assign them.

Creating a realm makes Keycloak add a bearer-only `<realm>-realm` client **to the master realm**.
That is stock Keycloak behaviour, not this library, but it will show up as unexpected churn if you
assert the master realm's clients against a validation file.

## 🔒 Hardening the master realm

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
timeout with a 1-hour maximum lifespan, brute-force protection, security headers, login events kept
for 90 days and admin events for 7, and a user profile that requires an email address. Every one
of them is a builder method away — override what does not fit and leave the rest. Security headers
you declare are merged over the defaults per header, so naming one keeps the others; an empty value
sends no header. An event expiration of `Duration.ZERO` keeps events forever.

### 🔑 Permanent admin

`provision()` creates the realm's permanent administrator, the human account that takes over from
Keycloak's temporary one. It is a `builder(...)` argument rather than an option, so it cannot be
forgotten.

The account is created **once**. If a user with that username already exists it is left entirely
untouched: no password reset, no role changes. Changing the configuration later therefore has no
effect on an admin that already exists.

`PermanentAdmin.Standard` sets an initial password the user must change on first login. A
configuration that would disable the required action behind that prompt is rejected by `build()`.
Use `PermanentAdmin.Custom` for other credential types.

The username has to be lower-case. Keycloak stores it that way regardless, so a mixed-case one
would leave your configuration and the realm disagreeing about the admin's name — and with it any
comparison your application makes against a token's `preferred_username`.

`PermanentAdmin.none()` skips the admin entirely. Together with the default
`deleteTemporaryAdminUsers(true)` that deliberately leaves the realm without administrative access,
for deployments that do not want a standing human admin — create one on demand with `kc.sh
bootstrap-admin user` when it is actually needed (see [Recovery](#recovery)).

### ⏳ Temporary admin users

Keycloak's bootstrap admin and any account created with `kc.sh bootstrap-admin` are marked as
temporary. Their credentials are usually well known (often `admin` / `admin`), so they should not
outlive the initial setup.

`provision()` deletes every account marked that way as its last step, right after the permanent
admin was created. Opt out with `deleteTemporaryAdminUsers(false)`.

### 💻 admin-cli

`admin-cli` is disabled by default: it allows signing in with a username and password alone,
bypassing the browser flow and any 2FA configured there. Keep it enabled only if your provisioning
connection relies on it as a fallback:

```java
MasterRealmConfig.builder(url, permanentAdmin)
    .builtInClients(List.of(
        BuiltInClientConfig.forClient(KeycloakProvidedClient.ADMIN_CLI).enabled(true).build()))
    .build();
```

Declarations are merged over the defaults per client, so naming `admin-cli` leaves the other
built-ins at theirs.

Disabling it does not lock you out of the admin console, which signs in through a different client.

### 🚑 Recovery

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
length or complexity. Once logged in, issues can be fixed. E.g. `admin-cli` can be re-enabled in
the admin console for renewed bootstrapping or credentials for the permanent admin can be reset.

> [!NOTE]
> The same applies when rotating the provisioning client's secret. If connecting with the new
> secret fails, the fallback authenticates through `admin-cli` and the bootstrap admin — both of
> which a hardened realm has disabled and deleted. Re-enable `admin-cli` first, or change the
> secret in the admin console to match your configuration.

## ♻️ Provisioning

The methods below take the **full desired state**. Anything they manage that is missing from what
you pass is reset, so repeated runs converge on your configuration. Objects Keycloak created are out
of scope — the fences below are what keeps them that way — except for its built-in clients, which
are reset to the configured default rather than deleted.

The same holds within an object: a field you leave `null` gets the value Keycloak gives a freshly
created object, so removing a setting restores Keycloak's default instead of keeping the last
value. Three exceptions treat `null` as "leave as is": a client's protocol mappers, its scope
assignments and its secret. A run with unchanged configuration sends no writes, with two
exceptions: a declared fixture password is set again every time, and SMTP with a password is
rewritten every time, because Keycloak returns the password masked so it can never match.

> [!IMPORTANT]
> Nothing coordinates concurrent runs. Two instances provisioning the same realm at once will race —
> conflicting writes, interleaved deletes — so run provisioning as a single job, or under a lock.

Every provisioning call returns a `ProvisioningResult` listing what it added, updated or removed,
with a diff for each update, so `hasChanges()` tells you whether a run changed anything. Each change
is also logged at INFO. Not included: the writes that bootstrap the provisioning client when a
connection is opened.

> [!WARNING]
> An incomplete list is destructive. Build the list from your own configuration every time — never
> from what happens to be in the realm.

| Method | What it resets                                                                                                                           |
|---|------------------------------------------------------------------------------------------------------------------------------------------|
| `provisionManagedClients` | deletes every client whose id carries the configured prefix (`system-` by default) and is not in the list                                |
| `provisionFlows` | deletes every flow that is not one of Keycloak's built-ins and is not in the list                                                        |
| `provisionExecutions` | deletes every execution of the named flows that is not in the list                                                                       |
| `provisionDefaultRoleComposites` | removes every composite of the realm's default role not in the list                                                                      |
| `provisionUserProfile` | deletes every user-profile attribute not declared and not a Keycloak built-in                                                            |
| `provisionRequiredActions` | unregisters every required action not in the list — an empty list means "not declared" here, so Keycloak's own are left alone |
| `provisionBuiltInClients` | resets every client Keycloak provides that is not in the list to its hardened default, which for `broker` and `admin-cli` means disabled |
| `provisionManagedClientScopes` | deletes every client scope whose name carries the prefix and is not in the list                                                          |
| `TestUserProvisioner.provisionUsers` | deletes **every user in the realm** that is not in the list — see [below](#provisioning-test-users-with-testuserprovisioner) |

### 🔐 Authentication flows

`provisionFlows` declares the realm's flows and `provisionExecutions` their executions, keyed by
the flow each belongs to:

```java
realmManager.provisionFlows(List.of(browserFlow));

Map<String, List<FlowExecution>> executions = new LinkedHashMap<>();
executions.put("my-browser-flow", List.of(
    new Authenticator("auth-cookie", ALTERNATIVE),
    new SubFlow("my-forms-subflow", "username, password and OTP", ALTERNATIVE)));
executions.put("my-forms-subflow", List.of(
    new Authenticator("auth-username-password-form", REQUIRED),
    new Authenticator("auth-otp-form", CONDITIONAL)));

realmManager.provisionExecutions(executions);
```

Entries are applied in iteration order, and a sub-flow only exists once the entry that declares it
has run — so use an ordered map and name a flow before the entry that fills it. `Map.of` iterates in
an unspecified order and will fail with a 404 on some runs and not others.

> [!IMPORTANT]
> Only top-level flows go to `provisionFlows`. A `SubFlow` entry creates the nested flow itself, so
> declaring it in both places fails with an HTTP 409.

The order you declare executions in is the order they run in. An execution keeps its identity across
runs, so reordering moves it rather than recreating it.

An execution is identified by its provider id, or by its name for a sub-flow, so the same
authenticator cannot appear twice in one flow — there would be nothing to tell the two apart on the
next run. Keycloak permits it; `provisionExecutions` rejects it before writing anything.

`FlowExecution` is a sealed type: an `Authenticator` names a provider id, a `SubFlow` names a flow
that the entry creates. It deliberately carries only what an execution update can
actually write — the requirement and the position. Everything else Keycloak either derives from the
flow tree or takes from the authenticator, so there is no customizer here; reach for
`getRealmResource()` if you need more.

### 👤 The user profile

`UserProfileConfig` declares the realm's user-profile attributes. What you declare is merged over
Keycloak's four built-ins by attribute name, so declaring one of your own keeps `username`,
`email`, `firstName` and `lastName` as they were.

```java
UserProfileConfig.builder()
    .attributes(List.of(AttributeConfig.builder("department").build()))
    .withoutBuiltInAttributes(LAST_NAME)
    .build();
```

A declared attribute is editable by admins only until you say otherwise with `editableBy`. Users
can change any attribute they may edit in the account console, so one that reaches a token and is
used for authorization has to stay admin-only. Give `editableBy` every role for an attribute users
fill in themselves — Keycloak accepts one that is required for users but not editable by them, and
only rejects it when a user opens their profile.

`withoutBuiltInAttributes` drops a built-in rather than overriding it. Only `firstName` and
`lastName` can go — Keycloak answers a profile without `username` or `email` with HTTP 400, and
`build()` rejects the attempt.

### 🧩 Keycloak's own clients

`MasterRealmProvisioner` provisions the six clients Keycloak creates in every realm. By default
`broker` is disabled and `account` keeps its standard flow off; everything else stays enabled.

```java
MasterRealmConfig.builder(url, permanentAdmin)
    .builtInClients(List.of(
        BuiltInClientConfig.forClient(SECURITY_ADMIN_CONSOLE).enabled(false).build()))
    .build();
```

What you declare is merged over those defaults per client, so the example above leaves `broker` and
`account` as they were. These clients are never created or deleted, only adjusted: one you do not
configure stays exactly as Keycloak made it.

`enabled` is written on every run, so dropping an entry re-enables its client — that is what makes
the configuration reversible. A `customizer` is applied as a delta and is **not** reverted when you
remove it, because there is no way to know what Keycloak's value for that field would have been. Set
the opposite explicitly to undo one.

`realm-management` cannot be disabled — Keycloak refuses it, and `build()` rejects the attempt.

### 🛡️ Client hardening

A managed client gets `fullScopeAllowed=false` unless it declares otherwise. Keycloak's own default
is `true`, which puts every role the user holds into that client's tokens regardless of its scope —
so a token leaked from a low-value application still asserts whatever else that user can do.

> [!IMPORTANT]
> This differs from Keycloak's default. If your application reads roles it was never scoped for,
> authorization starts failing after login succeeds — opt back in per client:

```java
client.setFullScopeAllowed(true);
```

Wildcard redirect URIs, post-logout redirect URIs and web origins are rejected: a wildcard lets any
URI under it receive the authorization code, so anyone who can host content there can complete the
flow.

### 🎫 Client scopes

Declare both scope lists on a client, or neither:

```java
client.setDefaultClientScopes(List.of("web-origins", "acr", "profile", "roles", "basic", "email"));
client.setOptionalClientScopes(List.of());
```

> [!NOTE]
> Naming either list opts the client out of the realm's default scopes for **both** — Keycloak's
> behaviour, not this library's. A client that declares only its optional scopes therefore ends up
with no default scopes either, and its tokens carry neither `preferred_username` nor realm roles
while login still succeeds, which makes the omission easy to miss. `provisionManagedClients` rejects
a half-declared client rather than letting that happen.

### 🏷️ Naming

`provisionManagedClients` only ever deletes clients carrying the configured prefix, which is what
keeps it away from anything you did not provision.

> [!IMPORTANT]
> A realm has to keep the same naming for its whole lifetime. Changing it orphans everything
> provisioned under the old prefix and pulls unrelated objects into scope for deletion.

> [!WARNING]
> The prefix fences clients and client scopes — **not flows or users**. `provisionFlows` is bounded
> by Keycloak's own built-in flag, so every flow it did not create is in scope, including ones made
> by hand in the admin console. `provisionUsers` has no fence at all.

```java
ManagedNaming.standard()                                     // clients, groups, roles, scopes
ManagedNaming.custom("acme-", "[Acme] ", "acme-", "acme-")
```

Groups get `[System] ` rather than `system-` because their names are shown to people in the admin
console. A scope name reaches the wire — a client sends `scope=system-orders` — which is the price
of Keycloak's own scopes never being at risk.

The prefix means "provisioned by this library" and nothing else, not a tenant or project marker, so
`standard()` is almost always right. The provisioning client deliberately carries no prefix: that
is what keeps your own `provisionManagedClients` call from deleting the identity you are connected
as.

A connection passes its naming to every `RealmManager` it creates, so the two cannot disagree:

```java
ManagedNaming naming = ManagedNaming.custom("acme-", "[Acme] ", "acme-", "acme-");

new KeycloakDefaultConnectionConfig(
    serverUrl,
    adminCredentials,
    new KeycloakProvisioningClientConfig("keycloak-provisioning", clientSecret),
    naming);
```

## ✅ Test fixtures

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
validation files so they stay stable across environments. Add your own normalizers by overriding
`keycloakValidationNormalizer()` and combining with `KeycloakTestTraits.super`.

`validateFlows` covers every flow in the realm, including Keycloak's own, which makes for a large
file before you add any of your own — `validateCustomFlows` asserts only the flows you provisioned.

Properties are serialized in alphabetical order; override `jsonMapper()` if you are migrating files
written with a different ordering.

Every assertion runs through a `RealmManager` you supply, so its identity has to be able to
authenticate against the realm as your configuration leaves it.

> [!TIP]
> The default user profile requires an email address for admins, so an admin without one is
> rejected when connecting via `admin-cli` with
> `{"error":"invalid_grant","error_description":"Account is not fully set up"}`. Connect via the
> provisioning client, via an admin that has an email address, or with a user profile that does not
> require one.

## 🧪 Provisioning test users with `TestUserProvisioner`

User provisioning lives in `TestUserProvisioner` rather than on `RealmManager`, because a user
list is rarely configuration: a realm that matters gets its accounts from people signing up or from
an identity provider, not from a list in your source code.

```java
new TestUserProvisioner(realmManager).provisionUsers(List.of(testUser));
```

Use it for realms whose whole user list is fixture data — a test container, a demo environment, a
seeded local setup.

> [!CAUTION]
> Users carry no naming prefix, so this is the one provisioning method with no fence at all: every
> account in the realm that is not in your list is deleted. Service accounts are unaffected, as
> Keycloak keeps them out of the user listing.

A declared password is set again on every run rather than diffed, which also restores one changed
in the admin console: Keycloak never returns its value, so there is no way to tell whether it still
matches. A temporary password therefore asks for a new one after every run. Only passwords are
supported, and provisioning refuses a realm whose password policy contains `passwordHistory` or
`passwordAge`, as both reject setting the current password again.

## 📋 Requirements

- Java 21+
- Keycloak 26.x (tested against 26.7.3)

## 🔗 Related Projects

- [validation-file-assertions]

[validation-file-assertions]: https://github.com/cronn/validation-file-assertions
[keycloak-client-26]: https://github.com/keycloak/keycloak-client/blob/main/docs/release-notes/26_0_0.adoc
