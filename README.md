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

## 🧭 Contents

- [Versioning](#-versioning)
- [Getting started](#-getting-started)
- [Connecting](#-connecting)
  - [DPoP-bound tokens](#-dpop-bound-tokens)
  - [Customizing the HTTP client](#-customizing-the-http-client)
- [Provisioning an application realm](#️-provisioning-an-application-realm)
- [Hardening the master realm](#-hardening-the-master-realm)
  - [Permanent admin](#-permanent-admin)
  - [Temporary admins](#-temporary-admins)
  - [admin-cli](#-admin-cli)
  - [Recovery](#-recovery)
- [Provisioning](#️-provisioning)
  - [Authentication flows](#-authentication-flows)
  - [The user profile](#-the-user-profile)
  - [Keycloak's own clients](#-keycloaks-own-clients)
  - [Client hardening](#️-client-hardening)
  - [Client scopes](#-client-scopes)
  - [Groups](#-groups)
  - [Users](#-users)
  - [Naming](#️-naming)
- [On-demand changes](#-on-demand-changes)
  - [On-demand groups](#-on-demand-groups)
  - [On-demand users](#-on-demand-users)
  - [On-demand clients and client scopes](#-on-demand-clients-and-client-scopes)
- [Test fixtures](#-test-fixtures)
- [Requirements](#-requirements)
- [Related Projects](#-related-projects)

## 🔢 Versioning

| Library Version | Keycloak Server | Java |
|-----------------|-----------------|------|
| 0.x             | 26.x            | 21+  |

Tested against 26.8.1. From `1.0.0` onwards the major version tracks the supported Keycloak major.
While `0.x` lasts, a minor release may still break the API.

The bundled `keycloak-admin-client` is an `api` dependency, so you will see it on your classpath at
version 26.0.12 even against a 26.8 server. That mismatch is expected: since 26.0.0 the client
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
> This deletes Keycloak's temporary admins once the permanent one exists, so the bootstrap
> credentials stop working. Keep the permanent admin's password, or pass
> `deleteTemporaryAdmins(false)` while you are trying things out.

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
> The same goes for every other `provision*` method. See [Provisioning](#️-provisioning) for what each
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

### 🔐 DPoP-bound tokens

`KeycloakProvisioningClientConfig.builder(...).useDPoP(true)` requests DPoP-bound access tokens for the
provisioning client's own connection and registers the client with
`dpop.bound.access.tokens=true`, so a stolen token cannot be replayed without the private key that
proved possession of it. Off by default, to leave existing consumers unaffected.

```java
KeycloakProvisioningClientConfig.builder("keycloak-provisioning", clientSecret)
    .useDPoP(true)
    .build();
```

DPoP needs a crypto provider on the runtime classpath to generate its key pair, which the admin
client does not bring on its own — add `org.keycloak:keycloak-crypto-default` (or an equivalent,
such as a FIPS provider) as a runtime dependency when you enable it
([keycloak/keycloak#27916](https://github.com/keycloak/keycloak/issues/27916)).

### 🌐 Customizing the HTTP client

`KeycloakDefaultConnectionConfig.builder(...).httpClientCustomizer(...)` runs a `Consumer<ResteasyClientBuilder>`
against every connection's HTTP client, after this library's own registrations (timeouts, request
logging) — use it to register a `ClientRequestFilter`, such as one that propagates tracing headers,
or to otherwise tune the underlying Resteasy client. A no-op by default. Custom
`KeycloakConnectionManager` implementations pass it on to `getDefaultKeycloakBuilder(...)`.

```java
KeycloakDefaultConnectionConfig.builder(serverUrl, adminCredentials, provisioningClientConfig)
    .httpClientCustomizer(builder -> builder.register(new MyTracingFilter()))
    .build();
```

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

The username and the email have to be lower-case. Keycloak stores both that way regardless, so a
mixed-case one would leave your configuration and the realm disagreeing about the admin's name —
and with it any comparison your application makes against a token's `preferred_username`.

`PermanentAdmin.none()` skips the admin entirely. Together with the default
`deleteTemporaryAdmins(true)` that deliberately leaves the realm without administrative access,
for deployments that do not want a standing human admin — create one on demand with `kc.sh
bootstrap-admin user` when it is actually needed (see [Recovery](#-recovery)).

### ⏳ Temporary admins

Keycloak's bootstrap admin and anything created with `kc.sh bootstrap-admin` are marked as
temporary. Their credentials are usually well known (often `admin` / `admin`), so they should not
outlive the initial setup.

`bootstrap-admin` creates either a user or a client, and both carry the marker. `provision()`
deletes both as its last step, right after the permanent admin was created — clients first, since
deleting one takes its service account with it. Opt out with `deleteTemporaryAdmins(false)`.

> [!IMPORTANT]
> A bootstrap admin *client* is invisible on the user side: Keycloak leaves service accounts out of
> the user list, so nothing short of deleting the client itself revokes its admin rights.

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
| `provisionManagedGroups` | deletes every group whose name carries the prefix and is not in the list, at every level of the tree                               |
| `provisionManagedUsers` | deletes every user whose username carries the prefix and is not in the list                                                            |

### 🔐 Authentication flows

`provisionFlows` declares the realm's flows and `provisionExecutions` their executions, keyed by
the flow each belongs to:

```java
AuthenticationFlowRepresentation browserFlow = new AuthenticationFlowRepresentation();
browserFlow.setAlias("my-browser-flow");
browserFlow.setDescription("Browser flow with OTP");
browserFlow.setProviderId("basic-flow");
browserFlow.setTopLevel(true);
browserFlow.setBuiltIn(false);

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

`providerId` is the flow type and has to be set — `basic-flow` for an ordinary flow — and
`topLevel` has to be `true`. Keycloak answers a flow without a type with "Duplicate resource", and
stores a flow that is not top-level where no later run can find it again, so `provisionFlows`
rejects both before writing anything.

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

`MasterRealmProvisioner` and `AppRealmProvisioner` both provision the six clients Keycloak creates
in every realm. By default `broker` and `admin-cli` are disabled and `account` keeps its standard
flow off; everything else stays enabled.

```java
MasterRealmConfig.builder(url, permanentAdmin)
    .builtInClients(List.of(
        BuiltInClientConfig.forClient(SECURITY_ADMIN_CONSOLE).enabled(false).build()))
    .build();
```

What you declare is merged over those defaults per client, so the example above leaves `broker` and
`account` as they were. These clients are never created or deleted, only adjusted.

`enabled` is written on every run, so dropping an entry returns its client to the default above —
`broker` and `admin-cli` to disabled, the rest to enabled. That is what makes the configuration
reversible. A `customizer` is applied as a delta and is **not** reverted when you
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

### 👪 Groups

Groups are declared as a tree with `GroupConfig`. Every group carries the prefix, sub-groups
included, so the fence works the same at each level: a group without it is never touched, and
neither is anything below it.

```java
ManagedNaming naming = ManagedNaming.standard();

realmManager.provisionManagedGroups(
    List.of(
        GroupConfig.builder(naming.groupName("Staff").value())
            .realmRoles("reader")
            .clientRoles("system-api", "use")
            .attribute("cost-center", "4711")
            .subGroups(
                GroupConfig.builder(naming.groupName("Editors").value())
                    .realmRoles("writer")
                    .build())
            .build()));

GroupAdministration groups = new GroupAdministration(realmManager);
groups.findGroupByPath("/[System] Staff/[System] Editors")
    .ifPresent(group -> groups.addMember(group.getId(), userId));
```

A group's declared realm and client roles are its full set of direct role mappings. The roles, and
the clients they belong to, have to exist before the groups that reference them.

> [!WARNING]
> Groups are matched by name. Renaming one, or moving it to another parent, deletes it and creates
> a new one — its members lose the membership. Deleting a managed group also deletes every group
> below it, including unprefixed ones created by hand.

If you keep your groups as an enum, map it to `GroupConfig` in your own code; the enum stays the
handle you use for memberships. Anything `GroupConfig` does not model is yours to change through
`GroupAdministration.findGroupByPath` and `getRealmResource()` after provisioning.

### 🧑 Users

`provisionManagedUsers` provisions the users whose username carries the prefix — technical
accounts, test users, a fixed set of administrators. Everyone else, whether they signed up, came
through an identity provider or were created with `UserAdministration`, is never touched. Only
users whose username contains the prefix are read from Keycloak, so a realm with many other users
is not loaded in full.

```java
realmManager.provisionManagedUsers(List.of(user)); // user.getUsername(): naming.username("ops").value()
```

The prefix has to be lower-case, as Keycloak stores usernames that way. Memberships are not part of
a provisioned user; grant them through `GroupAdministration`.

A declared password is set again on every run rather than diffed, which also restores one changed
in the admin console: Keycloak never returns its value, so there is no way to tell whether it still
matches. A temporary password therefore asks for a new one after every run. Only passwords are
supported, and provisioning refuses a realm whose password policy contains `passwordHistory` or
`passwordAge`, as both reject setting the current password again.

### 🏷️ Naming

`provisionManagedClients` only ever deletes clients carrying the configured prefix, which is what
keeps it away from anything you did not provision.

> [!IMPORTANT]
> A realm has to keep the same naming for its whole lifetime. Changing it orphans everything
> provisioned under the old prefix and pulls unrelated objects into scope for deletion.

> [!WARNING]
> The prefix fences clients, client scopes, groups and users — **not flows**. `provisionFlows` is
> bounded by Keycloak's own built-in flag, so every flow it did not create is in scope, including
> ones made by hand in the admin console.

```java
ManagedNaming.standard()                                     // clients, groups, roles, scopes, users
ManagedNaming.custom("acme-", "[Acme] ", "acme-", "acme-", "acme-")
```

Groups get `[System] ` rather than `system-` because their names are shown to people in the admin
console. A scope name reaches the wire — a client sends `scope=system-orders` — which is the price
of Keycloak's own scopes never being at risk.

The prefix means "provisioned by this library" and nothing else, not a tenant or project marker, so
`standard()` is almost always right. The provisioning client needs no prefix, and a connection
exempts its id from the fence either way, so `provisionManagedClients` never deletes the identity
you are connected as. Name it by your own convention if you already have one — a consumer adopting
this library one realm at a time keeps the id its other code authenticates with.

A connection passes its naming to every `RealmManager` it creates, so the two cannot disagree:

```java
ManagedNaming naming = ManagedNaming.custom("acme-", "[Acme] ", "acme-", "acme-", "acme-");

new KeycloakDefaultConnectionConfig(
    serverUrl,
    adminCredentials,
    new KeycloakProvisioningClientConfig("keycloak-provisioning", clientSecret),
    naming);
```

## ⚡ On-demand changes

Provisioning converges the realm on a fixed configuration. What appears while the application runs
— a new customer, a new employee — is written through the on-demand classes instead. They take
the same connection and the same `RealmManager`:

```java
GroupAdministration groups = new GroupAdministration(realmManager);
```

They only ever write objects **outside** the managed namespace, so provisioning never deletes what
they create, and they refuse to touch anything provisioning owns. Every write is strict: creating
something that exists, or changing something that does not, fails instead of falling back to the
other. Each one is logged like a provisioning change.

### 👪 On-demand groups

```java
String acme =
    groups.createGroup(
        GroupConfig.builder("Acme").attribute("customer-id", "42").realmRoles("reader").build());
groups.createSubGroup(acme, GroupConfig.builder("Buyers").build());
groups.addMember(acme, userId);

groups.findGroupsByAttribute("customer-id", "42");
```

`updateGroup` matches a group by its id, so a new name renames it and its members stay. It leaves
the sub-groups alone. `replaceGroupTree` also brings them to the declared ones and deletes any
other, along with its memberships. Sub-groups are matched by name, so renaming one that way
recreates it without its members. Find a group by an attribute of your own rather than by its name,
which may change.

Managed and unmanaged groups are never nested in each other: `createSubGroup` refuses a parent that
is managed or lies within a managed group, where the new group would be deleted along with it.
Memberships are references, not part of the tree, so users can join managed groups as well.

### 👤 On-demand users

```java
UserAdministration users = new UserAdministration(realmManager);

String userId = users.createUser(user);
users.resetPassword(userId, initialPassword, true);
```

`updateUser` takes the user's full desired state, like provisioning does: a field left `null` gets
the value of a newly created user, so an undeclared `enabled` disables the account. Memberships go
through `GroupAdministration`; a user declaring `groups` or roles is refused, since Keycloak would
ignore them. A client's service account cannot be changed or deleted on its own.

### 🔌 On-demand clients and client scopes

```java
new ClientAdministration(realmManager).createClient(client);
```

A client is validated and written exactly as `provisionManagedClients` writes one — the same
security defaults, wildcard checks and scope rules, its scope assignments and protocol mappers
included. `updateClient` matches by `clientId`. Keycloak's own clients and the provisioning client
are refused; configure the former with `provisionBuiltInClients`.

`ClientScopeAdministration` does the same for client scopes, matched by name, and refuses
Keycloak's own scopes such as `profile` or `roles`. Deleting a scope also removes it from every
client it is assigned to.

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
`validateCustomClients` and `validateCustomClientScopes` likewise leave out Keycloak's own clients and
client scopes.

`validateGroups` writes the whole group tree, each group with its realm and client role mappings,
and a second file with the members of every group, keyed by its path.

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

## 📋 Requirements

- Java 21+
- Keycloak 26.x (tested against 26.8.1)

## 🔗 Related Projects

- [validation-file-assertions]

[validation-file-assertions]: https://github.com/cronn/validation-file-assertions
[keycloak-client-26]: https://github.com/keycloak/keycloak-client/blob/main/docs/release-notes/26_0_0.adoc
