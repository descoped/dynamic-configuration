/**
 * A small application module that uses the library from outside, as a real consumer would.
 */
module consumer {
    requires io.descoped.dynamic.config;

    // Records bound by the library: exported to it only, nothing else is exported.
    exports consumer.bound to io.descoped.dynamic.config;
    // consumer (with internal.properties) and consumer.hidden stay encapsulated.
}
