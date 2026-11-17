# Dynamic Configuration

![Build Status](https://img.shields.io/github/actions/workflow/status/descoped/dynamic-configuration/ci.yml)
![Latest Tag](https://img.shields.io/github/v/tag/descoped/dynamic-configuration)
![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=descoped_dynamic-configuration&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=descoped_dynamic-configuration) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=descoped_dynamic-configuration&metric=coverage)](https://sonarcloud.io/summary/new_code?id=descoped_dynamic-configuration)
[![Snyk Security Score](https://snyk.io/test/github/descoped/dynamic-configuration/badge.svg)](https://snyk.io/test/github/descoped/dynamic-configuration)

Layered, immutable application configuration for Java 17+, built for services that run in containers and on the
module path.

- **Layered sources, later wins:** properties from the class path, from your own module, from files; secret files;
  environment variables; system properties; values from code; your own sources.
- **Twelve-factor overrides:** environment variables and system properties only override keys you declared, using the
  MicroProfile Config naming rule (`server.context-path` ← `SERVER_CONTEXT_PATH`).
- **Secrets as files:** one file per key, as Kubernetes and Docker mount them. Secrets are masked everywhere and no
  value is ever logged.
- **Profiles and references:** `application-test.properties` on top of `application.properties`, and
  `${key:default}` inside values.
- **Typed access that fails clearly:** numbers, booleans, `Duration`, enums, lists, any type via a converter, and
  binding to records. Errors name the key and where its value came from.
- **Immutable and thread-safe:** resolved once; `reload()` gives you a new snapshot.
- **A real JPMS module** (`io.descoped.dynamic.config`) whose only dependency is `slf4j-api`.

## Usage

```xml
<dependency>
    <groupId>io.descoped.config</groupId>
    <artifactId>dynamic-configuration</artifactId>
    <version>3.0.0</version>
</dependency>
```

```java
module com.acme.app {
    requires io.descoped.dynamic.config;
    exports com.acme.app.config to io.descoped.dynamic.config;   // only if you bind records from this package
}
```

```properties
# application.properties
server.host=0.0.0.0
server.port=8080
server.idle-timeout=30s
db.host=localhost
db.url=jdbc:postgresql://${db.host}/app
db.password=
```

```java
DynamicConfiguration config = DynamicConfiguration.builder()
        .classpath("application.properties")             // + application-<profile>.properties
        .file(Path.of("local_override.properties"))       // optional
        .directory(Path.of("/run/secrets"))               // optional, one file per key
        .environment()                                    // SERVER_PORT=9090, DB_HOST=db override declared keys
        .systemProperties()                               // -Dserver.port=9090 overrides server.port
        .build();

int port = config.getInt("server.port");
Duration idle = config.getDuration("server.idle-timeout");

// package com.acme.app.config;
// public record Server(String host, int port, Duration idleTimeout) {}
Server server = config.bind("server", Server.class);
```

## Sources

Sources are applied in the order you add them; a later source wins.

| Method | Reads | Missing |
|---|---|---|
| `classpath(name)` | The first match on the class path, via the thread context class loader | skipped |
| `resource(url)` | A URL your code looked up, e.g. `App.class.getResource("app.properties")` | skipped (also `null`) |
| `file(path)` / `requiredFile(path)` | A properties file, relative to the working directory | skipped / fails |
| `directory(path)` | One key per regular, non-hidden file; content is the value (one trailing line break removed); all secret | skipped |
| `environment()` / `environment(prefix)` | Overrides declared keys only | |
| `systemProperties()` / `systemProperties(prefix)` | Overrides declared keys only | |
| `values(map)` / `value(key, value)` | Values from code | |
| `source(ConfigurationSource)` | Your own source: YAML, JSON, a vault, ... | |

All properties are read as UTF-8 and keep their file order. A file that is not valid UTF-8, or has a malformed
`\uXXXX` escape, fails `build()` with an error naming the file; nothing is replaced silently. (2.x files in
ISO-8859-1 must be converted to UTF-8.)

**Modules.** Resource lookup in named modules is caller-sensitive. `classpath(name)` sees resources outside packages
(such as `application.properties` at the root of your jar) and in open packages. For a resource inside one of your own
encapsulated packages, look it up yourself and pass the URL: `resource(App.class.getResource("app.properties"))`. No
`opens` is needed.

### Environment variables

For key `k` the first variable that exists is used: `k`; `k` with every non-alphanumeric character replaced by `_`;
the same in upper case. With `environment("MYAPP_")` the prefix goes in front of each name. Environment variables and
system properties never add keys, so only what you declared is configurable: to let `DB_HOST` set the host, declare
`db.host` in a file. If two keys map to the same variable (`a.b` and `a-b`) and it is set, `build()` fails.

Without a prefix, every variable a container already has can override a key of the same name: a key `home`, `user`,
`path` or `lang` is overridden by `HOME`, `USER`, `PATH` or `LANG`. Prefer a prefix, `environment("MYAPP_")`, unless
your keys are all namespaced (`server.port`, `db.url`).

### Profiles

`profiles("test")` adds `application-test.properties` right after `application.properties`, and the same for every
`classpath`, `resource` and `file` source. Without `profiles(..)`, the profiles come from system property
`config.profiles`, else environment variable `CONFIG_PROFILES` (comma-separated). `profiles()` with no names turns
them off explicitly.

### References

`${key}` and `${key:default}` are resolved once, against the final layered values; defaults may nest. `$${` is a
literal `${`. A missing reference without a default, or a cycle, fails `build()` and lists every problem with the key
and its source.

Values from secret sources (`directory(..)`, and a `ConfigurationSource` whose `secret()` is true) are taken
literally: a generated password containing `${` is never parsed. Other values can still reference them,
`dsn=user:${db.password}`, and become secret themselves.

## Reading

| Method | |
|---|---|
| `find(key)` | `Optional<String>` |
| `get(key)`, `get(key, default)` | `String` |
| `getInt`, `getLong`, `getDouble`, `getBoolean` | strict; booleans are `true`/`false` only |
| `getDuration` | `PT30S`, `500ms`, `30s`, `5m`, `2h`, `1d` |
| `getEnum(key, Type.class)` | case-insensitive, `-` reads as `_` |
| `getList(key)` | comma-separated, trimmed |
| `get(key, URI::create)` | any type through a converter |
| `bind(prefix, Record.class)` | `idleTimeout` reads `idle-timeout`, then `idleTimeout`; nested records and `Optional` supported |
| `subset(prefix)` | the keys under a prefix, prefix removed |
| `asMap()` | all values, unmodifiable, in source order |

Every getter without a default throws `ConfigurationException` when the key is missing. A malformed value always
throws, even with a default: defaults cover *missing*, never *wrong*.

## Diagnostics and secrets

- `sourceOf(key)` tells where a value came from: `env SERVER_PORT`, `file /app/local_override.properties`,
  `classpath jar:file:/app/app.jar!/application.properties`.
- `describe()` (and `toString()`) lists every key, value and source, with secrets shown as `****`.
- Errors never quote a secret: a malformed secret is reported with the key, its source and the exception type only,
  with no cause attached, because parser messages (`For input string: "..."`) repeat their input.
- Secret keys: everything from `directory(..)`; keys whose last segment contains `password`, `passwd`, `secret`,
  `token`, `credential`, `private-key` or `api-key`; keys passed to `builder.secret(..)`; and any value that
  references a secret.
- Logging is slf4j at DEBUG: which sources were loaded and how many keys each had, which were not found, which keys
  were overridden. Values are never logged.

## Migrating from 2.x

3.0.0 is a clean break. 2.0.0 stays on Maven Central for Java 11.

| 2.x | 3.0 |
|---|---|
| `new StoreBasedDynamicConfiguration.Builder()` | `DynamicConfiguration.builder()` |
| `propertiesResource(path)` (class path, every match, last wins; then a file with the same path) | `classpath(name)` (first match) and/or `file(Path)` |
| `environment(prefix)` (adds every variable, raw names) | `environment(prefix)` overrides declared keys only, MicroProfile mapping |
| `systemProperties()` (adds every property) | `systemProperties()` overrides declared keys only |
| `values("k1", "v1", "k2", "v2")` | `values(Map.of(...))` or `value(k, v)` |
| `evaluateToString(key)` (null when missing) | `find(key)` or `get(key)` |
| `evaluateToInt(key)` | `getInt(key)` |
| `evaluateToBoolean(key)` (false for anything but "true") | `getBoolean(key)` (strict) |
| values read live on every call | one immutable snapshot; `reload()` for a new one |
| Java 11, no dependencies | Java 17, `slf4j-api` |

## Snapshots

SNAPSHOTs are not published. To try unreleased changes, install `master` into your local repository:

```bash
git clone https://github.com/descoped/dynamic-configuration && cd dynamic-configuration && ./mvnw install
```

## Building

```bash
./mvnw verify                 # tests on the module path
src/it/consumer/run.sh        # a separate named module uses the jar, on module and class path
```

`./mvnw` is the Maven Wrapper: it downloads the Maven version pinned in `.mvn/wrapper/maven-wrapper.properties` and
checks it against the SHA-256 recorded there. Java 17 or newer is needed to build.

### Reproducible builds

Builds are reproducible: the same tag gives byte-for-byte the same jar, sources jar and Javadoc jar. To check a
release against Maven Central:

```bash
git checkout 3.0.1 && ./mvnw -P central package -DskipTests
shasum -a 256 target/*.jar
```

Build with the same major JDK as `Build-Jdk-Spec` in the released jar's `META-INF/MANIFEST.MF`, then compare with the
`.sha256` files next to each jar on `repo1.maven.org`. The `.asc` signatures cannot be reproduced; check them with
`gpg --verify`. Releases before 3.0.1 were not built reproducibly.

CI builds and tests every pull request on JDK 17, the baseline, in one job; every merge to `master` also runs coverage
and SonarCloud. CI publishes nothing; releases are published to Maven Central by hand.
