package com.afs.jwalk;

import java.nio.file.Path;

/**
 * Main entry point for the JWalk fluent API.
 * <p>
 * JWalk provides a fluent builder for directory tree traversal with
 * glob/regex filtering, error handling, and parallel walking support.
 * </p>
 * <p>
 * Usage examples:
 * <pre>{@code
 * // List all .txt files
 * Walk.over("/path/to/dir")
 *     .glob("*.txt")
 *     .forEach(System.out::println);
 *
 * // Walk with depth limit and collect results
 * WalkResult result = Walk.over("/path")
 *     .maxDepth(3)
 *     .regex(".*\\.java$")
 *     .walk();
 *
 * // Parallel walk with error handling
 * Walk.over("/path")
 *     .glob("*.log")
 *     .parallel(4)
 *     .onError(ex -> System.err.println("Error: " + ex.getMessage()))
 *     .forEach(path -> process(path));
 * }</pre>
 * </p>
 */
public final class Walk {

    private Walk() {
        // Utility class — prevent instantiation
    }

    /**
     * Start building a walk over the given directory path.
     *
     * @param path the directory path string to walk
     * @return a new {@link WalkBuilder} for configuring the walk
     * @throws NullPointerException if path is null
     */
    public static WalkBuilder over(String path) {
        return new WalkBuilder(Path.of(path));
    }

    /**
     * Start building a walk over the given directory path.
     *
     * @param path the directory path to walk
     * @return a new {@link WalkBuilder} for configuring the walk
     * @throws NullPointerException if path is null
     */
    public static WalkBuilder over(Path path) {
        return new WalkBuilder(path);
    }
}
