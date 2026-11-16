/**
 * Layered, immutable application configuration.
 *
 * <p>A configuration is declared with {@link io.descoped.config.DynamicConfiguration#builder()} as an ordered list of
 * sources, where a source added later wins over one added earlier:
 *
 * <ul>
 *     <li>properties from the class path, from a URL the application located itself, or from a file;</li>
 *     <li>a directory of secret files, one file per key (Kubernetes and Docker secrets);</li>
 *     <li>environment variables and system properties, which only <em>override</em> keys other sources define;
 *     environment names follow the MicroProfile Config mapping ({@code a.b-c} is read from {@code A_B_C});</li>
 *     <li>programmatic values and application-supplied {@link io.descoped.config.ConfigurationSource}s.</li>
 * </ul>
 *
 * <p>Profiles add {@code name-<profile>.properties} siblings, values may reference other keys with
 * {@code ${key}} or {@code ${key:default}}, and the result is an immutable snapshot with typed getters, record
 * binding, provenance and masking of secrets.
 */
module io.descoped.dynamic.config {
    requires org.slf4j;

    exports io.descoped.config;
}
