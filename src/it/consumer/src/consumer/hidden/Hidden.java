package consumer.hidden;

/**
 * Public, but in a package that is not exported to the library.
 *
 * @param name the name
 */
public record Hidden(String name) {
}
