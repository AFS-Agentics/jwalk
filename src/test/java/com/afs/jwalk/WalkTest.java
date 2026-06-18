package com.afs.jwalk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for the JWalk library.
 * <p>
 * Tests cover: basic walking, glob/regex filtering, depth control,
 * symlink handling, directory inclusion, error handling, parallel walking,
 * filter composition, terminal operations, WalkResult validation,
 * and null/edge-case handling.
 * </p>
 */
@DisplayName("JWalk")
class WalkTest {

    @TempDir
    Path tempDir;

    private Path file1Txt;
    private Path file2Java;
    private Path subdir;
    private Path file3Txt;
    private Path file4Kt;

    @BeforeEach
    void setUp() throws IOException {
        // Create test directory structure:
        // tempDir/
        //   ├── file1.txt
        //   ├── file2.java
        //   ├── subdir/
        //   │   ├── file3.txt
        //   │   ├── file4.kt
        //   │   └── nested/
        //   │       └── file5.txt
        //   └── build/
        //       └── output.class

        file1Txt = Files.createFile(tempDir.resolve("file1.txt"));
        file2Java = Files.createFile(tempDir.resolve("file2.java"));

        subdir = Files.createDirectory(tempDir.resolve("subdir"));
        file3Txt = Files.createFile(subdir.resolve("file3.txt"));
        file4Kt = Files.createFile(subdir.resolve("file4.kt"));

        Path nested = Files.createDirectory(subdir.resolve("nested"));
        Files.createFile(nested.resolve("file5.txt"));

        Path buildDir = Files.createDirectory(tempDir.resolve("build"));
        Files.createFile(buildDir.resolve("output.class"));
    }

    // =======================================================================
    // Basic Walk Tests
    // =======================================================================

    @Nested
    @DisplayName("Basic walking")
    class BasicWalk {

        @Test
        @DisplayName("walk() on empty directory returns empty result")
        void walkEmptyDir(@TempDir Path emptyDir) {
            WalkResult result = Walk.over(emptyDir).walk();
            assertEquals(0, result.matchCount());
            assertTrue(result.isClean());
            assertNotNull(result.elapsedTime());
        }

        @Test
        @DisplayName("walk() returns all files when no filters set")
        void walkAllFiles() {
            WalkResult result = Walk.over(tempDir).walk();
            // Expect 5 files (file1.txt, file2.java, file3.txt, file4.kt, output.class)
            // file5.txt is nested inside subdir, so 6 total files
            assertEquals(6, result.matchCount(), "Should match all 6 files");
            assertTrue(result.isClean());
        }

        @Test
        @DisplayName("walk() returns WalkResult with correct metadata")
        void walkResultMetadata() {
            WalkResult result = Walk.over(tempDir).walk();
            assertEquals(6, result.matchCount());
            assertTrue(result.totalCount() >= 6, "totalCount should be >= matchCount");
            assertEquals(0, result.errorCount());
            assertTrue(result.elapsedTime().toMillis() >= 0);
        }

        @Test
        @DisplayName("toList() returns all matched paths")
        void toListReturnsPaths() {
            List<Path> paths = Walk.over(tempDir).toList();
            assertEquals(6, paths.size());
        }

        @Test
        @DisplayName("toStream() returns all matched paths as a stream")
        void toStreamReturnsPaths() {
            try (Stream<Path> stream = Walk.over(tempDir).toStream()) {
                List<Path> paths = stream.collect(Collectors.toList());
                assertEquals(6, paths.size());
            }
        }

        @Test
        @DisplayName("forEach() applies action to each matched path")
        void forEachAppliesAction() {
            List<Path> collected = new ArrayList<>();
            Walk.over(tempDir).forEach(collected::add);
            assertEquals(6, collected.size());
        }
    }

    // =======================================================================
    // Glob Filtering Tests
    // =======================================================================

    @Nested
    @DisplayName("Glob filtering")
    class GlobFiltering {

        @Test
        @DisplayName("glob(*.txt) matches only .txt files at any depth")
        void globTxt() {
            List<Path> paths = Walk.over(tempDir).glob("*.txt").toList();
            assertEquals(3, paths.size(), "Should match 3 .txt files");
            paths.forEach(p -> assertTrue(p.toString().endsWith(".txt")));
        }

        @Test
        @DisplayName("glob(*.java) matches only .java files")
        void globJava() {
            List<Path> paths = Walk.over(tempDir).glob("*.java").toList();
            assertEquals(1, paths.size());
            assertTrue(paths.get(0).toString().endsWith(".java"));
        }

        @Test
        @DisplayName("glob(*.kt) matches only .kt files")
        void globKt() {
            List<Path> paths = Walk.over(tempDir).glob("*.kt").toList();
            assertEquals(1, paths.size());
            assertTrue(paths.get(0).toString().endsWith(".kt"));
        }

        @Test
        @DisplayName("glob(*.class) matches only .class files")
        void globClass() {
            List<Path> paths = Walk.over(tempDir).glob("*.class").toList();
            assertEquals(1, paths.size());
            assertTrue(paths.get(0).toString().endsWith(".class"));
        }

        @Test
        @DisplayName("glob with no matches returns empty list")
        void globNoMatch() {
            List<Path> paths = Walk.over(tempDir).glob("*.py").toList();
            assertEquals(0, paths.size());
        }

        @Test
        @DisplayName("multiple globs are intersected (AND)")
        void multipleGlobsIntersection() {
            // Two globs that both match all files = same as one
            List<Path> paths = Walk.over(tempDir).glob("*.*").glob("*.txt").toList();
            assertEquals(3, paths.size(), "Should only match .txt files (intersection of *.* and *.txt)");
            paths.forEach(p -> assertTrue(p.toString().endsWith(".txt")));
        }
    }

    // =======================================================================
    // Regex Filtering Tests
    // =======================================================================

    @Nested
    @DisplayName("Regex filtering")
    class RegexFiltering {

        @Test
        @DisplayName("regex matches against full path")
        void regexFullPath() {
            // Match all .txt files using regex on full path
            List<Path> paths = Walk.over(tempDir).regex(".*\\.txt$").toList();
            assertEquals(3, paths.size());
            paths.forEach(p -> assertTrue(p.toString().endsWith(".txt")));
        }

        @Test
        @DisplayName("regex with complex pattern")
        void regexComplex() {
            // Match files with "file" in name followed by a digit
            List<Path> paths = Walk.over(tempDir).regex(".*file[0-9].*").toList();
            // file1.txt, file2.java, file3.txt, file4.kt, file5.txt = 5 files
            assertEquals(5, paths.size());
        }

        @Test
        @DisplayName("multiple regexes are intersected (AND)")
        void multipleRegexes() {
            List<Path> paths = Walk.over(tempDir)
                .regex(".*\\.txt$")
                .regex(".*file[0-9].*")
                .toList();
            // .txt files with "file" + digit: file1.txt, file3.txt, file5.txt
            assertEquals(3, paths.size());
        }
    }

    // =======================================================================
    // Filter Composition Tests
    // =======================================================================

    @Nested
    @DisplayName("Filter composition")
    class FilterComposition {

        @Test
        @DisplayName("glob + regex intersection works correctly")
        void globAndRegex() {
            // Glob: *.java, Regex: must contain "file2" somewhere in path
            // Result should match only file2.java (if it has "file2" in its name)
            List<Path> paths = Walk.over(tempDir)
                .glob("*.java")
                .regex(".*file2.*")
                .toList();
            assertEquals(1, paths.size());
            assertTrue(paths.get(0).toString().endsWith("file2.java"));
        }

        @Test
        @DisplayName("filter with no intersection returns empty")
        void noIntersection() {
            List<Path> paths = Walk.over(tempDir)
                .glob("*.txt")
                .regex(".*\\.java$")  // Can't be both .txt and .java
                .toList();
            assertEquals(0, paths.size());
        }
    }

    // =======================================================================
    // Depth Control Tests
    // =======================================================================

    @Nested
    @DisplayName("Depth control")
    class DepthControl {

        @Test
        @DisplayName("maxDepth(0) matches nothing (only root, no files)")
        void maxDepthZero() {
            List<Path> paths = Walk.over(tempDir).maxDepth(0).toList();
            assertEquals(0, paths.size());
        }

        @Test
        @DisplayName("maxDepth(1) matches only root-level files")
        void maxDepthOne() {
            List<Path> paths = Walk.over(tempDir).maxDepth(1).toList();
            // Only file1.txt and file2.java and the subdirs (if included)
            // Default is files only, no dirs
            assertEquals(2, paths.size(), "maxDepth(1) should find 2 root files");
        }

        @Test
        @DisplayName("maxDepth(2) includes files in subdir/ but not subdir/nested/")
        void maxDepthTwo() {
            List<Path> paths = Walk.over(tempDir).maxDepth(2).toList();
            // Root: file1.txt, file2.java (2)
            // subdir/: file3.txt, file4.kt (2)
            // build/: output.class (1)
            // Total: 5 (file5.txt is at depth 3: subdir/nested/file5.txt)
            assertEquals(5, paths.size(), "maxDepth(2) should find 5 files");
        }

        @Test
        @DisplayName("maxDepth with negative value throws")
        void negativeMaxDepth() {
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).maxDepth(-1)
            );
        }
    }

    // =======================================================================
    // Symlink Tests
    // =======================================================================

    @Nested
    @DisplayName("Symlink handling")
    class SymlinkHandling {

        @Test
        @DisplayName("symlinks are not followed by default")
        void symlinksNotFollowedByDefault() throws IOException {
            Path link = Files.createSymbolicLink(tempDir.resolve("link_to_txt"), file1Txt);
            // Without followSymlinks, the symlink itself (if treated as file) would appear
            // Let's count: default walk returns all 6 files (including the symlink as a file)
            List<Path> paths = Walk.over(tempDir).toList();
            assertTrue(paths.contains(link), "Symlink file should appear as a regular entry");
        }

        @Test
        @DisplayName("followSymlinks(true) allows traversal through symlinked directories")
        void followSymlinksIntoDir() throws IOException {
            Path subdirLink = Files.createSymbolicLink(tempDir.resolve("linked_dir"), subdir);
            // With followSymlinks, walking through the symlink should work
            // The symlink itself is a directory (file5.txt inside)
            List<Path> paths = Walk.over(tempDir).followSymlinks(true).glob("*.txt").toList();
            // file1.txt, file3.txt, file5.txt (3), no duplicates
            assertTrue(paths.size() >= 3, "Should find at least 3 .txt files");
        }
    }

    // =======================================================================
    // Directory Inclusion Tests
    // =======================================================================

    @Nested
    @DisplayName("Directory inclusion")
    class DirectoryInclusion {

        @Test
        @DisplayName("includeDirs(false) returns only files (default)")
        void includeDirsFalse() {
            List<Path> paths = Walk.over(tempDir).toList();
            paths.forEach(p -> assertTrue(Files.isRegularFile(p), "All results should be files"));
        }

        @Test
        @DisplayName("includeDirs(true) includes directories in results")
        void includeDirsTrue() {
            List<Path> paths = Walk.over(tempDir).includeDirs(true).toList();
            // Files: 6, Dirs: subdir, nested, build = 9 total
            assertTrue(paths.size() > 6, "Should include directories");
            assertTrue(paths.contains(subdir), "Should contain subdir");
        }
    }

    // =======================================================================
    // Error Handling Tests
    // =======================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("onError handler is called when directory doesn't exist")
        void onErrorNonExistent() {
            Path nonExistent = tempDir.resolve("does_not_exist");
            List<IOException> errors = new ArrayList<>();
            WalkResult result = Walk.over(nonExistent)
                .onError(errors::add)
                .walk();
            assertEquals(0, result.matchCount());
            assertTrue(result.errorCount() > 0 || errors.size() > 0,
                "Should report error for non-existent path");
        }

        @Test
        @DisplayName("walk continues after error (default handler)")
        void continueAfterError() {
            Path nonExistent = tempDir.resolve("does_not_exist");
            // Default handler silently ignores - should not crash
            WalkResult result = Walk.over(nonExistent).walk();
            assertEquals(0, result.matchCount());
        }
    }

    // =======================================================================
    // Parallel Walk Tests
    // =======================================================================

    @Nested
    @DisplayName("Parallel walking")
    class ParallelWalking {

        @Test
        @DisplayName("parallel walk returns same results as sequential")
        void parallelReturnsSameResults() {
            List<Path> sequential = Walk.over(tempDir).glob("*.txt").toList();
            List<Path> parallel = Walk.over(tempDir).glob("*.txt").parallel(4).toList();
            assertEquals(sequential.size(), parallel.size(),
                "Parallel should find same files as sequential");
        }

        @Test
        @DisplayName("parallel walk with 1 thread works")
        void parallelSingleThread() {
            List<Path> paths = Walk.over(tempDir).parallel(1).toList();
            assertEquals(6, paths.size());
        }

        @Test
        @DisplayName("parallel with forEach works")
        void parallelForEach() {
            List<Path> collected = new ArrayList<>();
            Walk.over(tempDir).parallel(2).forEach(collected::add);
            assertEquals(6, collected.size());
        }

        @Test
        @DisplayName("parallel threads less than 1 throws")
        void invalidParallelism() {
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).parallel(0)
            );
        }
    }

    // =======================================================================
    // Event Handler Tests
    // =======================================================================

    @Nested
    @DisplayName("Event handler")
    class EventHandlerTests {

        @Test
        @DisplayName("walk(WalkEventHandler) invokes onStart and onEnd")
        void startAndEndEvents() {
            List<String> events = new ArrayList<>();
            WalkEventHandler handler = new WalkEventHandler() {
                @Override
                public void onStart(Path root) {
                    events.add("start");
                }

                @Override
                public void onEnd(WalkResult result) {
                    events.add("end");
                }
            };
            Walk.over(tempDir).walk(handler);
            assertTrue(events.contains("start"));
            assertTrue(events.contains("end"));
        }

        @Test
        @DisplayName("walk(WalkEventHandler) invokes onFile for each matched file")
        void onFileEvents() {
            AtomicInteger fileCount = new AtomicInteger(0);
            WalkEventHandler handler = new WalkEventHandler() {
                @Override
                public void onFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    fileCount.incrementAndGet();
                }
            };
            Walk.over(tempDir).walk(handler);
            assertEquals(6, fileCount.get(), "Should fire onFile for all 6 files");
        }
    }

    // =======================================================================
    // Null / Edge Case Tests
    // =======================================================================

    @Nested
    @DisplayName("Null and edge cases")
    class NullAndEdgeCases {

        @Test
        @DisplayName("over(null) throws NullPointerException")
        void overNullPath() {
            assertThrows(NullPointerException.class, () -> Walk.over((Path) null));
        }

        @Test
        @DisplayName("over(null string) throws NullPointerException")
        void overNullString() {
            assertThrows(NullPointerException.class, () -> Walk.over((String) null));
        }

        @Test
        @DisplayName("glob(null) throws NullPointerException")
        void globNull() {
            assertThrows(NullPointerException.class, () ->
                Walk.over(tempDir).glob(null)
            );
        }

        @Test
        @DisplayName("regex(null) throws NullPointerException")
        void regexNull() {
            assertThrows(NullPointerException.class, () ->
                Walk.over(tempDir).regex(null)
            );
        }

        @Test
        @DisplayName("onError(null) throws NullPointerException")
        void onErrorNull() {
            assertThrows(NullPointerException.class, () ->
                Walk.over(tempDir).onError(null)
            );
        }

        @Test
        @DisplayName("forEach(null) throws NullPointerException")
        void forEachNull() {
            assertThrows(NullPointerException.class, () ->
                Walk.over(tempDir).forEach(null)
            );
        }

        @Test
        @DisplayName("walk(null handler) throws NullPointerException")
        void walkNullHandler() {
            assertThrows(NullPointerException.class, () ->
                Walk.over(tempDir).walk((WalkEventHandler) null)
            );
        }

        @Test
        @DisplayName("glob with invalid pattern throws IllegalArgumentException")
        void invalidGlobPattern() {
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).glob("[invalid")
            );
        }
    }

    // =======================================================================
    // Integration / Fluent API Tests
    // =======================================================================

    @Nested
    @DisplayName("Fluent API integration")
    class FluentApi {

        @Test
        @DisplayName("full fluent chain works end-to-end")
        void fullChain() {
            WalkResult result = Walk.over(tempDir)
                .glob("*.txt")
                .regex(".*file[135].*")
                .maxDepth(3)
                .includeDirs(false)
                .onError(ex -> {})
                .walk();

            assertEquals(3, result.matchCount(), "Should match file1.txt, file3.txt, file5.txt");
            assertTrue(result.isClean());
            assertTrue(result.elapsedTime().toMillis() >= 0);
        }

        @Test
        @DisplayName("walk result summary is non-empty")
        void resultSummary() {
            WalkResult result = Walk.over(tempDir).walk();
            String summary = result.summary();
            assertNotNull(summary);
            assertFalse(summary.isEmpty());
            assertTrue(summary.contains("matched"));
        }
    }
}
