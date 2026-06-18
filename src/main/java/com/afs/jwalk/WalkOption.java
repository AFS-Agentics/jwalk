package com.afs.jwalk;

import java.nio.file.FileVisitOption;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration options for directory tree walking.
 * <p>
 * Use {@link #DEFAULTS} for the default configuration, or create
 * custom instances via the builder methods.
 * </p>
 *
 * @param followSymlinks whether to follow symbolic links (default: {@code false})
 * @param maxDepth       maximum depth of directory traversal from the root (default: {@link Integer#MAX_VALUE})
 * @param includeDirs    whether to include directories in matched results (default: {@code false})
 */
public record WalkOption(
    boolean followSymlinks,
    int maxDepth,
    boolean includeDirs
) {

    /** Default walk options: no symlinks, unlimited depth, files only. */
    public static final WalkOption DEFAULTS = new WalkOption(false, Integer.MAX_VALUE, false);

    /**
     * Compact constructor for validation.
     *
     * @throws IllegalArgumentException if maxDepth is negative
     */
    public WalkOption {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0, got: " + maxDepth);
        }
    }

    /**
     * Returns a new {@code WalkOption} with the specified symlink behavior.
     *
     * @param follow {@code true} to follow symbolic links
     * @return a new WalkOption with updated symlink setting
     */
    public WalkOption withFollowSymlinks(boolean follow) {
        return new WalkOption(follow, maxDepth, includeDirs);
    }

    /**
     * Returns a new {@code WalkOption} with the specified max depth.
     *
     * @param depth the new max depth
     * @return a new WalkOption with updated depth
     * @throws IllegalArgumentException if depth is negative
     */
    public WalkOption withMaxDepth(int depth) {
        return new WalkOption(followSymlinks, depth, includeDirs);
    }

    /**
     * Returns a new {@code WalkOption} with the specified directory inclusion setting.
     *
     * @param include {@code true} to include directories in results
     * @return a new WalkOption with updated directory inclusion setting
     */
    public WalkOption withIncludeDirs(boolean include) {
        return new WalkOption(followSymlinks, maxDepth, include);
    }

    /**
     * Converts this {@code WalkOption} into a set of {@link FileVisitOption}
     * suitable for {@link java.nio.file.Files#walkFileTree}.
     *
     * @return an unmodifiable set of {@link FileVisitOption}s
     */
    public Set<FileVisitOption> toFileVisitOptions() {
        if (followSymlinks) {
            return EnumSet.of(FileVisitOption.FOLLOW_LINKS);
        }
        return Collections.emptySet();
    }

    /**
     * Merges this option with explicit option overrides, preferring non-default values.
     *
     * @param followSymlinks override for symlink behavior
     * @param maxDepth       override for max depth
     * @param includeDirs    override for directory inclusion
     * @return a new WalkOption with the merged settings
     */
    public WalkOption merge(Boolean followSymlinks, Integer maxDepth, Boolean includeDirs) {
        boolean newFollow = Objects.requireNonNullElse(followSymlinks, this.followSymlinks);
        int newDepth = Objects.requireNonNullElse(maxDepth, this.maxDepth);
        boolean newInclude = Objects.requireNonNullElse(includeDirs, this.includeDirs);
        return new WalkOption(newFollow, newDepth, newInclude);
    }
}
