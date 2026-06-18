package com.afs.jwalk;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Event handler interface for directory tree walking callbacks.
 * <p>
 * Implement this interface to receive fine-grained notifications during
 * a directory walk. All methods have empty default implementations, so
 * you only need to override the ones you care about.
 * </p>
 * <p>
 * Usage with {@link WalkBuilder}:
 * <pre>{@code
 * Walk.over("/path")
 *     .glob("*.txt")
 *     .walk(new WalkEventHandler() {
 *         @Override
 *         public void onFile(Path file, BasicFileAttributes attrs) {
 *             System.out.println("Found: " + file);
 *         }
 *         @Override
 *         public void onError(Path path, IOException exc) {
 *             System.err.println("Error at " + path + ": " + exc.getMessage());
 *         }
 *     });
 * }</pre>
 * </p>
 */
public interface WalkEventHandler {

    /**
     * Called when a regular file is encountered during the walk.
     *
     * @param file  the path of the file
     * @param attrs the basic file attributes of the file
     */
    default void onFile(Path file, BasicFileAttributes attrs) {
        // no-op
    }

    /**
     * Called when a directory is encountered during the walk.
     *
     * @param dir   the path of the directory
     * @param attrs the basic file attributes of the directory
     */
    default void onDirectory(Path dir, BasicFileAttributes attrs) {
        // no-op
    }

    /**
     * Called when an I/O error occurs during the walk.
     * <p>
     * Returning from this method allows the walk to continue
     * past the error. The walk will not abort unless you throw
     * an unchecked exception.
     * </p>
     *
     * @param path the path that caused the error
     * @param exc  the I/O exception that occurred
     */
    default void onError(Path path, IOException exc) {
        // no-op
    }

    /**
     * Called once when the walk starts.
     *
     * @param root the root directory being walked
     */
    default void onStart(Path root) {
        // no-op
    }

    /**
     * Called once when the walk completes, whether successfully or with errors.
     *
     * @param result the aggregated walk result
     */
    default void onEnd(WalkResult result) {
        // no-op
    }
}
