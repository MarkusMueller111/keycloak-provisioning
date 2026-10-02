# Contributing

## Where things live

- `RealmManager` — the hub. Every provisioning method lives here.
- `differ/` — one differ per entity type, all on top of `AbstractDiffer`.
- `connection/` — connecting and bootstrapping the provisioning client.
- `master/` — provisioning the master realm; the root package covers application realms.
- `src/testFixtures` — `KeycloakTestTraits`, **published alongside the library**, so a change there
  is as breaking for consumers as one in `src/main`.

## How provisioning works

Everything named `provision*` takes the full desired state and deletes what is not declared.

`AbstractDiffer` does not compare your declaration against the server's. It takes the **server's**
representation, applies the differ's `PropertyUpdater` to it, and compares the JSON before and
after:

```java
String oldStateAsJson = toJson(targetElement);
updater.update(targetElement, sourceElement);
String newStateAsJson = toJson(targetElement);
```

> [!IMPORTANT]
> A field the updater does not copy is invisible. It can never appear in a diff, is never written,
> and drift in it is never corrected — silently, while provisioning reports success. Add new fields
> to the updater, and say why in a comment when you deliberately leave one out.

A field the declaration leaves `null` is provisioned to the value Keycloak gives a freshly created
object, so removing a declaration restores the default. Keycloak ignores `null` on update, so the
updater has to send that default explicitly — `Declared` does this, and returns the server's own
value when it is already equivalent, so an unchanged object produces no diff. The exceptions,
where `null` means "leave as is": a client's protocol mappers, its scope assignments and its secret.

Every provisioning method has a `sendsNoWritesWhenRerun…` test: `assertChangesNothing` checks
both the reported `ProvisioningResult` and every write request at the HTTP level, including ones
no differ reports.

### Adding a provisioning method

1. Validate the whole desired list **before the first write**, so a contradiction cannot leave the
   realm half provisioned.
2. Fetch existing state filtered by the fence — never the unfiltered list.
3. Diff with `AbstractDiffer`, then add, update, delete.
4. Create before deleting where a rename would otherwise leave a window with neither.
5. Record every write through `Changes` right before sending it, and return its result. It also
   logs the change, so do not log writes separately.

The fence is what keeps deletion away from objects this library did not create. `ManagedNaming`
mints prefixed names for clients, client scopes, groups and roles; flows are fenced only by
Keycloak's own built-in flag, and users not at all.

> [!CAUTION]
> Before provisioning a new entity type, work out what a Keycloak built-in of that type
> is and how the fence keeps it out of scope — then write the test that proves it survives a
> provisioning declaring none of it. Keycloak's own client scopes make the point: deleting
> `profile` or `roles` strips claims from every token in the realm while login keeps working.

## Verify Keycloak's behaviour, never assume it

Keycloak frequently behaves differently from what its API suggests, and sometimes
from what its documentation states.

One source of confusion is that the admin API reuses one representation class across contexts and silently
ignores the parts that do not apply to the one you are in. `ClientRepresentation` is the same type
whether you create a client, update it, or read a list of them — but which of its fields take
effect differs in each case, and nothing tells you which. A field you set is simply dropped, the
call returns success, and the realm is not what you declared.

So the shape of the API tells you what you *may* set, never what *will* apply. Before writing a
comment, a javadoc line or a test that asserts what Keycloak does, prove it against a container. A
throwaway test that prints what the server returns takes two minutes and is worth more than any
amount of reasoning from the model classes.

Findings worth knowing, all verified this way — note how many are the same field behaving
differently depending on where it is sent:

- `clients().findAll()` returns the real secret for confidential clients — but a public client has
  none, and a `null` secret in an update payload makes Keycloak generate a new one.
- Protocol mappers apply on a **client's** update, but not on a **client scope's** — the scope
  needs its own mappers endpoint.
- A client's *scope assignments* are ignored on its update as well, and need their own endpoints.
- `searchByUsername(name, true)` is case-insensitive, but Keycloak stores usernames lower-cased.
- An authentication execution's update honours only `requirement` and `priority`.
- An update ignores every `null` field. A string is cleared only by sending `""`.
- A client's attributes and flow-binding overrides are merged on update: a key left out keeps its
  value, and one sent as `""` is removed. A client scope's attributes cannot be removed at all —
  `""` is stored as `""`. A realm attribute left out of the update is removed.
- A protocol mapper's update replaces its whole config, but creating one fills in
  `introspection.token.claim` and `userinfo.token.claim` from `access.token.claim` and
  `id.token.claim`.
- A client created without web origins gets them derived from its redirect URIs.
- A user's reset-password endpoint ignores the credential type and stores any value as the
  password. The `passwordHistory` and `passwordAge` policies reject a reset to the current password.

## Tests

Tests run against a real Keycloak in a container, so Docker has to be available. Two base classes:

- `SharedKeycloakTest` — one container for the whole class, realm dropped between tests. Use this.
- `OwnKeycloakTest` — a container per test. Slow, but required when a test hardens the master realm
  irreversibly or must observe the state before the provisioning client exists.

### Validation-file snapshots

Some tests assert against committed snapshots using [validation-file-assertions]. To accept
a changed snapshot, copy from `data/test/output/` into `data/test/validation/` and re-run.

> [!WARNING]
> Never copy from `tmp/*.raw` — that is the pre-normalization dump and still contains raw UUIDs,
> timestamps and URLs, so those snapshots fail on every subsequent run. A validation file whose
> first line is `=== new file "..." ===` was never accepted: that marker is a diff header, not
> content, and must not be committed.

## Building

```bash
./gradlew build
./gradlew spotlessApply   # formatting, enforced by the build
```

The Keycloak image is built once from `src/test/resources/keycloak/Dockerfile` and cached, so only
the first run pays for it.

> [!IMPORTANT]
> `build` does not run `javadoc`. A dangling `{@link}` compiles and ships silently, so run both
> before opening a pull request:
>
> ```bash
> ./gradlew build javadoc
> ```

## Conventions

Integration tests over unit tests. Write a unit test only for intricate logic isolated in one place
that makes no Keycloak call — `MasterRealmConfigTest` and `ManagedNamingTest` are the accepted
precedents. A unit test over code that talks to Keycloak pins *our idea* of how Keycloak behaves,
which is exactly the assumption that keeps turning out to be wrong.

Prove a test fails without its fix: remove the fix, confirm the intended test fails, put it back. A
test that passes either way pins nothing. When an existing test gets in the way, rewrite it rather
than adding a second one beside it.

Comments are few and short, and document only what the code cannot show: surprising Keycloak
behaviour, a workaround and why it exists, a non-obvious constraint. Not how the code came to be
this way — a bug it used to have, a design that was rejected — which belongs in the commit message.
A test name already says what is asserted, so a comment there earns its place only by stating an
external fact behind the assertion.

[validation-file-assertions]: https://github.com/cronn/validation-file-assertions
