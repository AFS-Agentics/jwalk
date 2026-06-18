package com.afs.jwalk;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fluent builder for configuring and executing directory tree walks.
 * <p>
 * Create an instance via {@link Walk#over(String)} or {@link Walk#over(Path)},
 * then chain configuration methods and call a terminal operation:
 * </p>
 * <pre>{@code
 * WalkResult result = Walk.over("/path")
 *     .glob("*.java")
 *     .maxDepth(5)
 *     .followSymlinks(true)
 *     .onError(ex -> System.err.println(ex))
 *     .walk();
 * }</pre>
 * <p>
 * <strong>Terminal operations:</strong>
 * <ul>
 *   <li>{@link #walk()} — execute the walk and return a {@link WalkResult}</li>
 *   <li>{@link #walk(WalkEventHandler)} — execute with event callbacks</li>
 *   <li>{@link #forEach(Consumer)} — execute and apply action to each matched path</li>
 *   <li>{@link #toList()} — execute and collect matched paths into a list</li>
 *   <li>{@link #toStream()} — execute and return matched paths as a stream</li>
 * </ul>
 * </p>
 */
public class WalkBuilder {

    private final Path startPath;
    private final List<PathMatcher> globMatchers = new ArrayList<>();
    private final List<Pattern> regexPatterns = new ArrayList<>();
    private int maxDepth = Integer.MAX_VALUE;
    private boolean followSymlinks = false;
    private boolean includeDirs = false;
    private Consumer<IOException> errorHandler = ex -> {};
    private int parallelism = 0; // 0 = sequential

    /**
     * Package-private constructor. Use {@link Walk#over(String)} or {@link Walk#over(Path)}.
     */
    WalkBuilder(Path startPath) {
        Objects.requireNonNull(startPath, "startPath must not be null");
        if (startPath.toString().isEmpty()) {
            throw new IllegalArgumentException("Path must not be null or empty");
        }
        this.startPath = startPath.toAbsolutePath().normalize();
    }

    // -----------------------------------------------------------------------
    // Fluent configuration methods
    // -----------------------------------------------------------------------

    /**
     * Add a glob pattern filter. Only paths whose filename matches the glob
     * will be included. Multiple globs are combined with AND (intersection).
     * <p>
     * The glob is matched against the path's <em>filename</em> (not the full path),
     * so {@code *.txt} matches {@code .txt} files at any depth.
     * </p>
     *
     * @param pattern the glob pattern (e.g., {@code *.java}, {@code *.txt})
     * @return this builder for chaining
     * @throws NullPointerException          if pattern is null
     * @throws IllegalArgumentException      if the pattern is syntactically invalid
     * @throws FileSystemNotFoundException   if the default filesystem is not available
     */
    public WalkBuilder glob(String pattern) {
        Objects.requireNonNull(pattern, "glob pattern must not be null");
        try {
            // Strip any path prefix (like "**/" in "**/*.txt") since globs are
            // matched against the filename only, not the full path.
            String filenamePattern = pattern;
            int lastSlash = pattern.lastIndexOf('/');
            if (lastSlash >= 0) {
                filenamePattern = pattern.substring(lastSlash + 1);
            }
            globMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + filenamePattern));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid glob pattern: '" + pattern + "'", e);
        }
        return this;
    }

    /**
     * Add a regex pattern filter. Only paths whose <em>full path string</em>
     * matches the regex will be included. Multiple regexes are combined with
     * AND (intersection).
     *
     * @param pattern the regex pattern (e.g., {@code .*\\.java$})
     * @return this builder for chaining
     * @throws NullPointerException     if pattern is null
     * @throws IllegalArgumentException if the pattern is syntactically invalid
     */
    public WalkBuilder regex(String pattern) {
        Objects.requireNonNull(pattern, "regex pattern must not be null");
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("Pattern must not be null or empty");
        }
        try {
            regexPatterns.add(Pattern.compile(pattern));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid regex pattern: '" + pattern + "'", e);
        }
        return this;
    }

    /**
     * Set the maximum depth of directory traversal. Depth 0 means only the
     * root directory itself (no children), depth 1 includes immediate children, etc.
     *
     * @param depth the maximum depth (must be >= 0)
     * @return this builder for chaining
     * @throws IllegalArgumentException if depth is negative
     */
    public WalkBuilder maxDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0, got: " + depth);
        }
        this.maxDepth = depth;
        return this;
    }

    /**
     * Set whether to follow symbolic links during traversal.
     * <p>
     * Default is {@code false}. When disabled, symlinks are not traversed
     * but may appear as regular files depending on the target type.
     * </p>
     *
     * @param follow {@code true} to follow symbolic links
     * @return this builder for chaining
     */
    public WalkBuilder followSymlinks(boolean follow) {
        this.followSymlinks = follow;
        return this;
    }

    /**
     * Set whether to include directories in the matched results.
     * <p>
     * Default is {@code false} (only files are matched). When enabled,
     * directories that pass the filters are included alongside files.
     * </p>
     *
     * @param include {@code true} to include directories in results
     * @return this builder for chaining
     */
    public WalkBuilder includeDirs(boolean include) {
        this.includeDirs = include;
        return this;
    }

    /**
     * Set a handler for I/O errors encountered during the walk.
     * <p>
     * The default handler silently ignores errors. When a handler is set,
     * the walk continues past the error rather than aborting.
     * </p>
     *
     * @param handler a consumer that processes I/O exceptions
     * @return this builder for chaining
     * @throws NullPointerException if handler is null
     */
    public WalkBuilder onError(Consumer<IOException> handler) {
        Objects.requireNonNull(handler, "error handler must not be null");
        this.errorHandler = handler;
        return this;
    }

    /**
     * Enable parallel walking with the specified number of threads.
     * <p>
     * Uses a {@link ForkJoinPool} to traverse the directory tree concurrently.
     * Parallelism happens at the directory level: different subdirectories
     * are walked in parallel.
     * </p>
     *
     * @param threads the number of parallel threads (must be >= 1)
     * @return this builder for chaining
     * @throws IllegalArgumentException if threads is less than 1
     */
    public WalkBuilder parallel(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("parallel threads must be >= 1, got: " + threads);
        }
        this.parallelism = threads;
        return this;
    }

    // -----------------------------------------------------------------------
    // Terminal operations
    // -----------------------------------------------------------------------

    /**
     * Execute the walk and return the result.
     *
     * @return a {@link WalkResult} containing matched paths and statistics
     */
    public WalkResult walk() {
        return doWalk(null);
    }

    /**
     * Execute the walk with an event handler and return the result.
     *
     * @param handler the event handler to receive callbacks during the walk
     * @return a {@link WalkResult} containing matched paths and statistics
     * @throws NullPointerException if handler is null
     */
    public WalkResult walk(WalkEventHandler handler) {
        Objects.requireNonNull(handler, "event handler must not be null");
        return doWalk(handler);
    }

    /**
     * Execute the walk and apply the given action to each matched path.
     *
     * @param action the action to apply to each matched path
     * @throws NullPointerException if action is null
     */
    public void forEach(Consumer<? super Path> action) {
        Objects.requireNonNull(action, "action must not be null");
        List<Path> paths = doWalk(null).matchedPaths();
        paths.forEach(action);
    }

    /**
     * Execute the walk and return the matched paths as a list.
     *
     * @return an unmodifiable list of matched paths
     */
    public List<Path> toList() {
        return doWalk(null).matchedPaths();
    }

    /**
     * Execute the walk and return the matched paths as a stream.
     *
     * @return a stream of matched paths
     */
    public Stream<Path> toStream() {
        return doWalk(null).matchedPaths().stream();
    }

    // -----------------------------------------------------------------------
    // Internal implementation
    // -----------------------------------------------------------------------

    /**
     * Core walk execution. Handles both sequential and parallel modes.
     */
    private WalkResult doWalk(WalkEventHandler handler) {
        Instant start = Instant.now();

        WalkOption option = new WalkOption(followSymlinks, maxDepth, includeDirs);

        if (handler != null) {
            handler.onStart(startPath);
        }

        if (parallelism > 0) {
            return doParallelWalk(option, handler, start);
        } else {
            return doSequentialWalk(option, handler, start);
        }
    }

    /**
     * Sequential walk using {@link Files#walkFileTree}.
     */
    private WalkResult doSequentialWalk(WalkOption option, WalkEventHandler handler, Instant start) {
        List<Path> results = new ArrayList<>();
        long[] totalCount = {0};
        long[] errorCount = {0};

        try {
            Set<FileVisitOption> opts = option.toFileVisitOptions();
            Files.walkFileTree(startPath, opts, maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    totalCount[0]++;
                    if (handler != null) {
                        handler.onFile(file, attrs);
                    }
                    // walkFileTree calls visitFile for directories when maxDepth
                    // prevents descending into them. We must check the actual type.
                    if (attrs.isDirectory()) {
                        if (includeDirs && matchesFilters(file)) {
                            results.add(file);
                        }
                    } else {
                        if (matchesFilters(file)) {
                            results.add(file);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(startPath)) {
                        totalCount[0]++;
                        if (handler != null) {
                            handler.onDirectory(dir, attrs);
                        }
                        if (includeDirs && matchesFilters(dir)) {
                            results.add(dir);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    errorCount[0]++;
                    if (handler != null) {
                        handler.onError(file, exc);
                    }
                    errorHandler.accept(exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errorCount[0]++;
            if (handler != null) {
                handler.onError(startPath, e);
            }
            errorHandler.accept(e);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        WalkResult result = new WalkResult(results, totalCount[0], errorCount[0], elapsed);

        if (handler != null) {
            handler.onEnd(result);
        }

        return result;
    }

    /**
     * Parallel walk using a {@link ForkJoinPool} and recursive actions.
     */
    private WalkResult doParallelWalk(WalkOption option, WalkEventHandler handler, Instant start) {
        List<Path> results = Collections.synchronizedList(new ArrayList<>());
        long[] totalCount = {0};
        long[] errorCount = {0};

        // Handle maxDepth(0) specially: walkFileTree calls visitFile for the root
        // directory at the boundary. We need consistent behavior in parallel mode.
        if (option.maxDepth() == 0) {
            synchronized (totalCount) {
                totalCount[0]++;
            }
            if (handler != null) {
                try {
                    BasicFileAttributes rootAttrs = Files.readAttributes(
                        startPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    handler.onDirectory(startPath, rootAttrs);
                } catch (IOException e) {
                    // Skip handler call if attributes can't be read
                }
            }
            if (option.includeDirs() && matchesFilters(startPath)) {
                results.add(startPath);
            }

            Duration elapsed = Duration.between(start, Instant.now());
            WalkResult result = new WalkResult(
                new ArrayList<>(results),
                totalCount[0],
                errorCount[0],
                elapsed
            );

            if (handler != null) {
                handler.onEnd(result);
            }

            return result;
        }

        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            WalkTask rootTask = new WalkTask(
                startPath, option, handler, results, totalCount, errorCount,
                globMatchers, regexPatterns, errorHandler, 0
            );
            pool.invoke(rootTask);
        } finally {
            pool.shutdown();
        }

        Duration elapsed = Duration.between(start, Instant.now());
        WalkResult result = new WalkResult(
            new ArrayList<>(results),
            totalCount[0],
            errorCount[0],
            elapsed
        );

        if (handler != null) {
            handler.onEnd(result);
        }

        return result;
    }

    /**
     * Check if a path matches all configured glob and regex filters.
     * <p>
     * If no filters are configured, all paths match.
     * Globs are matched against the filename, regexes against the full path string.
     * Multiple filters are combined with AND (all must match).
     * </p>
     */
    boolean matchesFilters(Path path) {
        // Check glob matchers (against filename)
        Path fileName = path.getFileName();
        for (PathMatcher matcher : globMatchers) {
            if (fileName == null || !matcher.matches(fileName)) {
                return false;
            }
        }

        // Check regex patterns (against full path string)
        String pathStr = path.toString();
        for (Pattern pattern : regexPatterns) {
            if (!pattern.matcher(pathStr).matches()) {
                return false;
            }
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // Recursive task for parallel walking
    // -----------------------------------------------------------------------

    /**
     * A {@link RecursiveAction} that walks a single directory.
     * Subdirectories are processed as child tasks via {@link #invokeAll}.
     */
    private static class WalkTask extends RecursiveAction {

        private final Path dir;
        private final WalkOption option;
        private final WalkEventHandler handler;
        private final List<Path> results;          // thread-safe (synchronizedList)
        private final long[] totalCount;           // accessed from pool threads
        private final long[] errorCount;           // accessed from pool threads
        private final List<PathMatcher> globMatchers;
        private final List<Pattern> regexPatterns;
        private final Consumer<IOException> errorHandler;
        private final int depth;

        WalkTask(
            Path dir, WalkOption option, WalkEventHandler handler,
            List<Path> results, long[] totalCount, long[] errorCount,
            List<PathMatcher> globMatchers, List<Pattern> regexPatterns,
            Consumer<IOException> errorHandler, int depth
        ) {
            this.dir = dir;
            this.option = option;
            this.handler = handler;
            this.results = results;
            this.totalCount = totalCount;
            this.errorCount = errorCount;
            this.globMatchers = globMatchers;
            this.regexPatterns = regexPatterns;
            this.errorHandler = errorHandler;
            this.depth = depth;
        }

        @Override
        protected void compute() {
            if (depth > option.maxDepth()) {
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                List<WalkTask> subTasks = new ArrayList<>();

                for (Path entry : stream) {
                    BasicFileAttributes attrs;
                    try {
                        attrs = readAttributes(entry);
                    } catch (IOException e) {
                        synchronized (errorCount) {
                            errorCount[0]++;
                        }
                        if (handler != null) {
                            handler.onError(entry, e);
                        }
                        errorHandler.accept(e);
                        continue;
                    }

                    if (attrs.isDirectory()) {
                        // Count the directory
                        synchronized (totalCount) {
                            totalCount[0]++;
                        }
                        if (handler != null) {
                            handler.onDirectory(entry, attrs);
                        }

                        // Check if directory matches and should be added
                        Path fileName = entry.getFileName();
                        boolean matches = matchesGlob(fileName) && matchesRegex(entry);
                        if (option.includeDirs() && matches) {
                            results.add(entry);
                        }

                        // Recurse into subdirectory if within depth
                        if (depth < option.maxDepth()) {
                            subTasks.add(new WalkTask(
                                entry, option, handler, results, totalCount, errorCount,
                                globMatchers, regexPatterns, errorHandler, depth + 1
                            ));
                        }
                    } else if (attrs.isRegularFile() || attrs.isSymbolicLink()) {
                        // Count the file
                        synchronized (totalCount) {
                            totalCount[0]++;
                        }
                        if (handler != null) {
                            handler.onFile(entry, attrs);
                        }

                        // Check if file matches filters
                        Path fileName = entry.getFileName();
                        if (matchesGlob(fileName) && matchesRegex(entry)) {
                            results.add(entry);
                        }
                    }
                }

                // Fork all subdirectory tasks
                if (!subTasks.isEmpty()) {
                    invokeAll(subTasks);
                }

            } catch (IOException e) {
                synchronized (errorCount) {
                    errorCount[0]++;
                }
                if (handler != null) {
                    handler.onError(dir, e);
                }
                errorHandler.accept(e);
            }
        }

        private BasicFileAttributes readAttributes(Path entry) throws IOException {
            if (option.followSymlinks()) {
                return Files.readAttributes(entry, BasicFileAttributes.class);
            } else {
                // Don't follow symlinks when reading attributes
                return Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
        }

        private boolean matchesGlob(Path fileName) {
            if (globMatchers.isEmpty()) return true;
            if (fileName == null) return false;
            for (PathMatcher matcher : globMatchers) {
                if (!matcher.matches(fileName)) return false;
            }
            return true;
        }

        private boolean matchesRegex(Path entry) {
            if (regexPatterns.isEmpty()) return true;
            String pathStr = entry.toString();
            for (Pattern pattern : regexPatterns) {
                if (!pattern.matcher(pathStr).matches()) return false;
            }
            return true;
        }
    }
}
