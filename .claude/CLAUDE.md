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
- **Publishing.** Nothing but a release goes to Maven Central, through the Central Portal (profiles `central,sign`),
  by hand and only with the owner's approval; the `central` profile's enforcer execution `only-releases-to-central`
  refuses a SNAPSHOT at `validate`. CI publishes nothing to Maven Central.
- **SNAPSHOTs.** SNAPSHOTs go only to maven.descoped.io (Descoped's AWS CodeArtifact), from CI's `snapshot` job: on a
  push to master, or by `workflow_dispatch` on a branch as `X.Y.Z-<branch>-SNAPSHOT`. The job names the target on its
  command line (`-DaltSnapshotDeploymentRepository`), and the POM has no `distributionManagement` at all: that is
  what makes a release version fail to deploy there. Never add one. No AWS account ID or CodeArtifact endpoint in
  any public file (POM, workflow, deployed POM, CI log).

## Commands

```sh
./mvnw verify                       # compile with -Xlint:all, tests on the module path (JUnit 6)
./mvnw verify javadoc:javadoc       # plus Javadoc with doclint, warnings fail the build
src/it/consumer/run.sh              # after package: a separate named module uses the jar, module and class path
./mvnw verify -P coverage           # JaCoCo report in target/site/jacoco
./mvnw install                      # the current SNAPSHOT into ~/.m2 for local consumers
gh workflow run ci.yml --ref <branch>  # publishes X.Y.Z-<branch>-SNAPSHOT to maven.descoped.io
```

Always `./mvnw`, never a system `mvn`, so every build uses the same Maven.

## Maven Wrapper

`.mvn/wrapper/maven-wrapper.properties` pins Maven (script-only wrapper, no jar in the repository) and its
`distributionSha256Sum`. When Maven is bumped (Renovate opens the PR but does not update the checksum):

1. Download `apache-maven-<version>-bin.zip` and check it against Apache's `.sha512` from downloads.apache.org.
2. Put its SHA-256 (`shasum -a 256`) in `distributionSha256Sum`.
3. `./mvnw -v` must print the new version.

`.mvn/maven.config` passes `-Dmaven.repository.credentialScope=id` to every build (CI, by hand, the release
checkout). Maven 3.10 binds a server's credentials to the hosts it knows for that id, and `central` is also Maven
Central's id; without the flag no token reaches `central.sonatype.com` and a deploy fails with 401.

## Reproducible builds

`project.build.outputTimestamp` in the POM makes builds reproducible; the release plugin updates it, never edit it by
hand. `.gitattributes` keeps every text file LF on any OS, so the sources jar is the same everywhere.
`./mvnw artifact:check-buildplan -P central` checks the plugins for known reproducibility issues.

Tests run with slf4j-simple at DEBUG writing to `target/test-slf4j.log`; `SecretsTest` asserts no value reaches it.
The environment and system properties are injected in tests through the builder's package-private
`environmentProvider` / `systemPropertiesProvider`.
