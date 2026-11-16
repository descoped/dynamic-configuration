package consumer;

import consumer.bound.Settings;
import consumer.hidden.Hidden;
import io.descoped.config.ConfigurationException;
import io.descoped.config.DynamicConfiguration;

import java.time.Duration;
import java.util.Map;

/** Exercises the public API from a separate module. Exits with status 1 on the first failed check. */
public final class Main {

    private Main() {
    }

    /**
     * Runs the checks.
     *
     * @param args {@code module} when started on the module path, {@code classpath} otherwise
     */
    public static void main(String[] args) {
        boolean modulePath = args.length > 0 && args[0].equals("module");
        check(DynamicConfiguration.class.getModule().isNamed() == modulePath,
                "library runs as a named module: " + modulePath);

        DynamicConfiguration config = DynamicConfiguration.builder()
                .classpath("consumer.properties")                              // root resource: never encapsulated
                .resource(Main.class.getResource("internal.properties"))       // encapsulated package, own lookup
                .build();

        check("hello from the module root".equals(config.get("greeting")), "classpath root resource");
        check("from an encapsulated package".equals(config.get("internal")), "resource(URL) from own package");

        Settings settings = config.bind("settings", Settings.class);
        check(settings.equals(new Settings("consumer", Duration.ofSeconds(2))), "record in qualified-exported package");

        try {
            Hidden hidden = config.bind("settings", Hidden.class);
            check(!modulePath && "consumer".equals(hidden.name()), "class path has no module encapsulation");
        } catch (ConfigurationException e) {
            check(modulePath, "only the module path refuses a non-exported record");
            check(e.getMessage().contains("exports consumer.hidden to io.descoped.dynamic.config"),
                    "error names the missing exports line: " + e.getMessage());
        }

        check(DynamicConfiguration.builder().values(Map.of("k", "v")).build().getList("k").size() == 1, "typed reads");
        System.out.println("consumer OK (" + (modulePath ? "module path" : "class path") + ")");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            System.err.println("FAILED: " + what);
            System.exit(1);
        }
    }
}
