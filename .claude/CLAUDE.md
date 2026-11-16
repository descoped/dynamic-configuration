# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

`io.descoped.config:dynamic-configuration`, JPMS module `io.descoped.dynamic.config`: a layered, immutable
configuration library for Java services. Owned by Descoped; designed here, after no other configuration library.
`README.md` is the user documentation and must stay true when behaviour changes.

## Invariants (never violate)

- **Java 17 baseline** (`maven.compiler.release` 17): every class file is Java 17 whatever JDK builds it. CI builds
  and tests on JDK 17 only, in one job (`ci.yml`); newer JDKs run the same bytecode.
- **JPMS first.** Exported: `io.descoped.config` only (`DynamicConfiguration` with its `Builder`,
  `ConfigurationSource`, `ConfigurationException`). Everything else lives in `io.descoped.config.internal`, which is
  never exported. No deep reflection; users never need `opens`. Record binding uses public reflection only.
- **One runtime dependency: `slf4j-api`** (`requires`, not `requires transitive`; no slf4j type in the API). Adding a
  runtime dependency is the owner's decision.
- **Always the latest stable version** of every library, plugin and action. Pre-releases only on request. Old and
  outdated code and configuration is removed, not carried along.
- **Immutable snapshot.** `build()` resolves everything once; reads never touch files, the environment or system
  properties. `reload()` returns a new snapshot.
- **Overrides never add keys.** Environment variables (MicroProfile Config name mapping) and system properties only
  override keys other sources define.
- **Secrets.** No configuration value is ever logged, at any level. Secret values are masked as `****` in
  `describe()`, `toString()` and exception messages, and never reach an exception's cause chain (parser messages quote
  their input). Values from secret sources are taken literally, never interpolated.
- **Errors name the key and its source**, including reference errors and unreadable files (malformed UTF-8 or
  escapes fail, never replaced silently). A default covers a missing value, never a malformed one.
- `DynamicConfiguration` is sealed; its only implementation is the package-private `Snapshot` in the same package
  (a sealed type on the class path requires its permitted subclasses in the same package).
- **Publishing.** Every merge to `master` publishes a SNAPSHOT to the Central Portal snapshot repository (`ci.yml`,
  step "Publish SNAPSHOT"). Releases go through the Central Portal (profiles `central,sign`), only with the owner's approval;
  CI never publishes a release.
- **No AI attribution** in commit messages or pull requests.

## Commands

```sh
mvn verify                       # compile with -Xlint:all, tests on the module path (JUnit 6)
mvn verify javadoc:javadoc       # plus Javadoc with doclint, warnings fail the build
src/it/consumer/run.sh           # after package: a separate named module uses the jar, module path and class path
mvn verify -P coverage           # JaCoCo report in target/site/jacoco
mvn install                      # 3.0.0-SNAPSHOT into ~/.m2 for local consumers
mvn deploy -P central,secrets    # SNAPSHOT to the Central Portal by hand (token from ~/.mavenrc)
```

Tests run with slf4j-simple at DEBUG writing to `target/test-slf4j.log`; `SecretsTest` asserts no value reaches it.
The environment and system properties are injected in tests through the builder's package-private
`environmentProvider` / `systemPropertiesProvider`.
