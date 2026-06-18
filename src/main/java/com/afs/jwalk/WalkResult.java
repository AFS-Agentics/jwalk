package com.afs.jwalk;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The result of a directory tree walk.
 * <p>
 * Contains the list of matched paths, along with aggregate statistics
 * about the walk (total files visited, error count, elapsed time).
 * </p>
 *
 * @param matchedPaths the list of paths that matched the configured filters (unmodifiable)
 * @param totalCount   the total number of files and directories visited during the walk
 * @param errorCount   the number of I/O errors encountered during the walk
 * @param elapsedTime  the wall-clock time the walk took
 */
public record WalkResult(
    List<Path> matchedPaths,
    long totalCount,
    long errorCount,
    Duration elapsedTime
) {

    /**
     * Compact constructor that makes a defensive copy of the matched paths list.
     */
    public WalkResult {
        matchedPaths = List.copyOf(matchedPaths);
        Objects.requireNonNull(elapsedTime, "elapsedTime must not be null");
    }

    /**
     * Returns the number of paths that matched the filters.
     *
     * @return the match count
     */
    public int matchCount() {
        return matchedPaths.size();
    }

    /**
     * Returns {@code true} if no errors occurred during the walk.
     *
     * @return {@code true} if the walk completed without errors
     */
    public boolean isClean() {
        return errorCount == 0;
    }

    /**
     * Returns a human-readable summary of the walk result.
     *
     * @return a summary string
     */
    public String summary() {
        return String.format(
            "Walked %d paths, matched %d, %d errors in %d ms",
            totalCount,
            matchCount(),
            errorCount,
            elapsedTime.toMillis()
        );
    }

    @Override
    public String toString() {
        return summary();
    }
}
