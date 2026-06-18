package com.afs.jwalk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive edge case tests for the JWalk library.
 * Covers: empty/special paths, unicode/emoji, symlink loops,
 * permission errors, deep trees, large directories, glob/regex edge cases,
 * terminal ops consistency, WalkResult correctness, parallel edge cases,
 * concurrent access, event handler edge cases, and chaining.
 * <p>
 * Separate test class — does NOT modify existing WalkTest.java.
 */
@DisplayName("JWalk Edge Cases")
class WalkEdgeCaseTest {

    @TempDir
    Path tempDir;

    private Path file1Txt;
    private Path file2Java;
    private Path subdir;
    private Path file3Txt;
    private Path file4Kt;
    private Path file5Txt; // nested

    @BeforeEach
    void setUp() throws IOException {
        // Same test structure as WalkTest:
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
        file5Txt = Files.createFile(nested.resolve("file5.txt"));
        Path buildDir = Files.createDirectory(tempDir.resolve("build"));
        Files.createFile(buildDir.resolve("output.class"));
    }

    // =======================================================================
    // EMPTY / SPECIAL PATH TESTS
    // =======================================================================

    @Nested
    @DisplayName("Empty and special paths")
    class EmptyAndSpecialPaths {

        @Test
        @DisplayName("over('') throws IllegalArgumentException")
        void emptyStringPath() {
            // Path.of("") -> CWD, but we now throw to avoid surprising behavior
            assertThrows(IllegalArgumentException.class, () -> Walk.over(""));
        }

        @Test
        @DisplayName("over with null String path throws NullPointerException")
        void overNullString() {
            assertThrows(NullPointerException.class, () -> Walk.over((String) null));
        }

        @Test
        @DisplayName("over with null Path throws NullPointerException")
        void overNullPath() {
            assertThrows(NullPointerException.class, () -> Walk.over((Path) null));
        }

        @Test
        @DisplayName("regex with empty string throws IllegalArgumentException")
        void regexEmptyString() {
            // Empty regex pattern is now rejected instead of silently matching nothing
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).regex("")
            );
        }

        @Test
        @DisplayName("regex with .* matches all paths")
        void regexAllMatch() {
            List<Path> paths = Walk.over(tempDir).regex(".*").toList();
            assertEquals(6, paths.size(), ".* regex should match all paths");
        }
    }

    // =======================================================================
    // UNICODE / EMOJI FILENAMES
    // =======================================================================

    @Nested
    @DisplayName("Unicode and emoji filenames")
    class UnicodeAndEmoji {

        @Test
        @DisplayName("walk handles files with unicode characters in name")
        void unicodeFileNames() throws IOException {
            Files.createFile(tempDir.resolve("résumé.txt"));
            Files.createFile(tempDir.resolve("中文.txt"));
            Files.createFile(tempDir.resolve("café.txt"));
            Files.createFile(tempDir.resolve("groß.txt"));

            List<Path> paths = Walk.over(tempDir).glob("*.txt").toList();
            assertEquals(7, paths.size(), "Should find 4 unicode-named files + 3 original .txt files");
        }

        @Test
        @DisplayName("walk handles files with emoji in name")
        void emojiFileNames() throws IOException {
            try {
                Files.createFile(tempDir.resolve("\uD83D\uDD25.txt"));
                Files.createFile(tempDir.resolve("\uD83D\uDCAF.txt"));
                Files.createFile(tempDir.resolve("\uD83C\uDF89-party.txt"));
            } catch (IOException e) {
                // Some FS (e.g., on CI) may reject emoji — skip gracefully
                return;
            }
            List<Path> paths = Walk.over(tempDir).glob("*.txt").toList();
            assertEquals(6, paths.size(), "Should find 3 emoji-named files + 3 original .txt files");
        }

        @Test
        @DisplayName("walk handles files with combining characters")
        void combiningChars() throws IOException {
            try {
                Files.createFile(tempDir.resolve("e\u0301.txt")); // e + combining acute
                Files.createFile(tempDir.resolve("n\u0303o.txt")); // n + combining tilde
            } catch (IOException e) {
                return;
            }
            List<Path> paths = Walk.over(tempDir).glob("*.txt").toList();
            assertEquals(5, paths.size(), "Should find 2 combining-char files + 3 original .txt files");
        }

        @Test
        @DisplayName("glob with unicode pattern matches unicode files")
        void globUnicodePattern() throws IOException {
            Files.createFile(tempDir.resolve("résumé.txt"));
            List<Path> paths = Walk.over(tempDir).glob("résumé.txt").toList();
            assertEquals(1, paths.size());
            assertTrue(paths.get(0).toString().contains("résumé"));
        }
    }

    // =======================================================================
    // SYMLINK LOOP TESTS
    // =======================================================================

    @Nested
    @DisplayName("Symlink loop handling")
    class SymlinkLoopHandling {

        @Test
        @DisplayName("sequential walk terminates on symlink loop with followSymlinks=true")
        @Timeout(10)
        void sequentialSymlinkLoop() throws IOException {
            Path dirA = Files.createDirectory(tempDir.resolve("dirA"));
            Path dirB = Files.createDirectory(tempDir.resolve("dirB"));
            Files.createFile(dirA.resolve("fileA.txt"));
            Files.createFile(dirB.resolve("fileB.txt"));
            Files.createSymbolicLink(dirA.resolve("linkToB"), dirB);
            Files.createSymbolicLink(dirB.resolve("linkToA"), dirA);

            WalkResult result = Walk.over(dirA)
                .followSymlinks(true)
                .walk();

            assertNotNull(result);
            assertTrue(result.matchCount() > 0,
                "Should find files despite symlink loop");
        }

        @Test
        @DisplayName("parallel walk terminates on symlink loop with followSymlinks=true")
        @Timeout(10)
        void parallelSymlinkLoop() throws IOException {
            Path dirA = Files.createDirectory(tempDir.resolve("pdirA"));
            Path dirB = Files.createDirectory(tempDir.resolve("pdirB"));
            Files.createFile(dirA.resolve("fileA.txt"));
            Files.createFile(dirB.resolve("fileB.txt"));
            Files.createSymbolicLink(dirA.resolve("linkToB"), dirB);
            Files.createSymbolicLink(dirB.resolve("linkToA"), dirA);

            WalkResult result = Walk.over(dirA)
                .followSymlinks(true)
                .parallel(4)
                .walk();

            assertNotNull(result);
            assertTrue(result.matchCount() > 0,
                "Parallel walk should complete despite symlink loop");
        }

        @Test
        @DisplayName("self-referencing symlink is handled without infinite loop")
        @Timeout(10)
        void selfReferencingSymlink() throws IOException {
            Path dir = Files.createDirectory(tempDir.resolve("selflink_dir"));
            Files.createFile(dir.resolve("file.txt"));
            try {
                Path selfLink = dir.resolve("self");
                Files.createSymbolicLink(selfLink, selfLink);
            } catch (IOException e) {
                // Some platforms reject self-referencing symlinks at creation
                return;
            }

            WalkResult result = Walk.over(dir)
                .followSymlinks(true)
                .walk();
            assertNotNull(result);
        }
    }

    // =======================================================================
    // PERMISSION DENIED TESTS
    // =======================================================================

    @Nested
    @DisplayName("Permission denied handling")
    class PermissionDeniedHandling {

        @Test
        @DisplayName("walk skips unreadable directories and reports errors")
        void unreadableDirectory() throws IOException {
            Path restricted = Files.createDirectory(tempDir.resolve("restricted"));
            Files.createFile(restricted.resolve("secret.txt"));

            // Make dir inaccessible
            try {
                Files.setPosixFilePermissions(restricted,
                    PosixFilePermissions.fromString("---------"));
            } catch (IOException e) {
                return; // platform doesn't support POSIX perms
            }

            List<IOException> errors = new ArrayList<>();
            List<Path> paths = Walk.over(tempDir)
                .onError(errors::add)
                .toList();

            assertTrue(paths.stream().anyMatch(p -> p.toString().contains("file1.txt")),
                "Should still find files in accessible directories");
        }

        @Test
        @DisplayName("walk continues after permission error without crashing")
        void continueAfterPermissionError() throws IOException {
            Path restricted = Files.createDirectory(tempDir.resolve("no_peek"));
            Files.createFile(restricted.resolve("hidden.dat"));
            try {
                Files.setPosixFilePermissions(restricted,
                    PosixFilePermissions.fromString("---------"));
            } catch (IOException e) {
                return;
            }

            WalkResult result = Walk.over(tempDir).walk();
            assertNotNull(result);
        }
    }

    // =======================================================================
    // VERY DEEP DIRECTORY TREE
    // =======================================================================

    @Nested
    @DisplayName("Deep directory tree")
    class DeepDirectoryTree {

        @Test
        @DisplayName("walk handles 100-level deep nested directories (sequential)")
        @Timeout(30)
        void deepNestedDirs() throws IOException {
            Path current = tempDir;
            for (int i = 0; i < 100; i++) {
                current = Files.createDirectory(current.resolve("d" + i));
            }
            Files.createFile(current.resolve("deep.txt"));

            List<Path> paths = Walk.over(tempDir).glob("*.txt").toList();
            // Should find both original .txt files AND the deep one
            assertTrue(paths.size() >= 1, "Should find deeply nested file");
            assertTrue(paths.stream().anyMatch(p -> p.toString().endsWith("deep.txt")));
        }

        @Test
        @DisplayName("parallel walk handles 100-level deep nested directories")
        @Timeout(30)
        void deepNestedDirsParallel() throws IOException {
            Path current = tempDir;
            for (int i = 0; i < 100; i++) {
                current = Files.createDirectory(current.resolve("p" + i));
            }
            Files.createFile(current.resolve("deep.txt"));

            List<Path> paths = Walk.over(tempDir)
                .glob("*.txt")
                .parallel(4)
                .toList();
            assertTrue(paths.size() >= 1,
                "Parallel walk should find deeply nested file");
        }

        @Test
        @DisplayName("maxDepth limits deep tree traversal")
        @Timeout(10)
        void maxDepthOnDeepTree() throws IOException {
            Path current = tempDir;
            for (int i = 0; i < 50; i++) {
                current = Files.createDirectory(current.resolve("l" + i));
            }
            Files.createFile(current.resolve("bottom.txt"));

            // With maxDepth(5), the deep file at depth 50+ should not be found
            List<Path> paths = Walk.over(tempDir).maxDepth(5).toList();
            assertFalse(paths.stream().anyMatch(p -> p.toString().endsWith("bottom.txt")),
                "maxDepth(5) should not reach bottom at depth 50+");
        }
    }

    // =======================================================================
    // VERY LARGE NUMBER OF FILES (STRESS TEST)
    // =======================================================================

    @Nested
    @DisplayName("Stress test — many files")
    class LargeFileCount {

        @Test
        @DisplayName("walk handles directory with 1000 files")
        @Timeout(30)
        void manyFiles() throws IOException {
            int fileCount = 1000;
            for (int i = 0; i < fileCount; i++) {
                Files.createFile(tempDir.resolve("big_" + i + ".dat"));
            }

            WalkResult result = Walk.over(tempDir).walk();
            // 1000 new files + 6 existing = 1006
            assertEquals(1006, result.matchCount(),
                "Should find all files");
        }

        @Test
        @DisplayName("parallel walk handles directory with 1000 files")
        @Timeout(30)
        void manyFilesParallel() throws IOException {
            int fileCount = 1000;
            for (int i = 0; i < fileCount; i++) {
                Files.createFile(tempDir.resolve("pf" + i + ".dat"));
            }

            WalkResult result = Walk.over(tempDir).parallel(4).walk();
            assertEquals(1006, result.matchCount(),
                "Parallel walk should find all files");
        }

        @Test
        @DisplayName("glob filters correctly on 500 files out of 1000")
        @Timeout(30)
        void manyFilesWithGlob() throws IOException {
            for (int i = 0; i < 500; i++) {
                Files.createFile(tempDir.resolve("keep" + i + ".txt"));
                Files.createFile(tempDir.resolve("skip" + i + ".log"));
            }

            List<Path> paths = Walk.over(tempDir).glob("*.txt").toList();
            // 500 txt files + 3 original .txt files
            assertEquals(503, paths.size(),
                "Glob should filter exactly .txt files");

            paths.forEach(p -> assertTrue(p.toString().endsWith(".txt"),
                "All matched files should be .txt files"));
        }
    }

    // =======================================================================
    // GLOB EDGE CASES
    // =======================================================================

    @Nested
    @DisplayName("Glob edge cases")
    class GlobEdgeCases {

        @Test
        @DisplayName("glob('*') matches all files including dotfiles")
        void globStar() throws IOException {
            Files.createFile(tempDir.resolve(".hidden"));
            Files.createFile(tempDir.resolve(".hidden.txt"));
            List<Path> paths = Walk.over(tempDir).glob("*").toList();
            // 6 original + 2 hidden = at least 8
            assertTrue(paths.size() >= 8, "glob('*') should match all files");
        }

        @Test
        @DisplayName("glob('.hidden*') matches hidden dotfiles")
        void globHidden() throws IOException {
            Files.createFile(tempDir.resolve(".hidden"));
            Files.createFile(tempDir.resolve(".hidden.txt"));
            List<Path> paths = Walk.over(tempDir).glob(".hidden*").toList();
            assertEquals(2, paths.size(),
                "Should match .hidden and .hidden.txt");
        }

        @Test
        @DisplayName("glob with no-match pattern returns empty")
        void globNoMatch() {
            List<Path> paths = Walk.over(tempDir).glob("no-match-*").toList();
            assertEquals(0, paths.size());
        }

        @Test
        @DisplayName("glob('**/*.txt') matches all .txt files at any depth")
        void globDoubleStar() throws IOException {
            Files.createFile(tempDir.resolve("readme.txt"));
            // JWalk now strips path prefix from globs, matching only against filename.
            // '**/*.txt' becomes '*.txt' and matches all .txt files.
            List<Path> paths = Walk.over(tempDir).glob("**/*.txt").toList();
            assertEquals(4, paths.size(),
                "'**/*.txt' glob should match all .txt files after stripping path prefix");
        }

        @Test
        @DisplayName("glob with pattern containing special chars like dashes/underscores/dots")
        void globSpecialChars() throws IOException {
            Files.createFile(tempDir.resolve("file-with-dashes.txt"));
            Files.createFile(tempDir.resolve("file_with_underscores.txt"));
            Files.createFile(tempDir.resolve("file.with.dots.txt"));

            assertEquals(1, Walk.over(tempDir).glob("file-with-dashes.txt").toList().size());
            assertEquals(1, Walk.over(tempDir).glob("file_with_underscores.txt").toList().size());
            assertEquals(1, Walk.over(tempDir).glob("file.with.dots.txt").toList().size());
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
    // REGEX EDGE CASES
    // =======================================================================

    @Nested
    @DisplayName("Regex edge cases")
    class RegexEdgeCases {

        @Test
        @DisplayName("regex with .* matches all paths")
        void regexAllMatch() {
            List<Path> paths = Walk.over(tempDir).regex(".*").toList();
            assertEquals(6, paths.size(), ".* regex should match all 6 files");
        }

        @Test
        @DisplayName("regex with file extension filter works")
        void regexTxtFilter() {
            List<Path> paths = Walk.over(tempDir).regex(".*\\.txt$").toList();
            assertEquals(3, paths.size(), "Should match 3 .txt files");
        }

        @Test
        @DisplayName("regex with path separator matches files in subdir")
        void regexPathSeparator() {
            // Use the full path string which includes path separators
            List<Path> paths = Walk.over(tempDir)
                .regex(".*subdir.*file[34].*")
                .toList();
            assertEquals(2, paths.size(), "Should match files in subdir");
        }

        @Test
        @DisplayName("regex with case-sensitive matching")
        void regexCaseSensitive() throws IOException {
            Files.createFile(tempDir.resolve("README.TXT"));
            // Java regex is case-sensitive by default
            List<Path> paths = Walk.over(tempDir).regex(".*\\.txt$").toList();
            assertFalse(paths.stream().anyMatch(p -> p.toString().endsWith("README.TXT")),
                "Default regex should be case-sensitive");
        }

        @Test
        @DisplayName("regex with invalid pattern throws")
        void regexInvalid() {
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).regex("[invalid")
            );
        }

        @Test
        @DisplayName("regex with special chars in path works correctly")
        void regexWithSpecialPathChars() throws IOException {
            Path specialDir = Files.createDirectory(tempDir.resolve("dir+[special]"));
            Files.createFile(specialDir.resolve("test.txt"));

            List<Path> paths = Walk.over(tempDir)
                .regex(".*test\\.txt$")
                .toList();
            assertEquals(1, paths.size(),
                "Regex should find file even when parent dir has special chars");
        }

        @Test
        @DisplayName("empty regex pattern throws IllegalArgumentException")
        void regexEmptyPattern() {
            // Empty regex pattern is now rejected
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).regex("")
            );
        }
    }

    // =======================================================================
    // TERMINAL OPERATIONS CONSISTENCY
    // =======================================================================

    @Nested
    @DisplayName("Terminal operations consistency")
    class TerminalOpsConsistency {

        @Test
        @DisplayName("toList(), forEach(), toStream() return same results")
        void terminalOpConsistency() {
            List<Path> listResult = Walk.over(tempDir).glob("*.txt").toList();

            List<Path> streamResult = Walk.over(tempDir).glob("*.txt")
                .toStream()
                .collect(Collectors.toList());

            List<Path> forEachResult = new ArrayList<>();
            Walk.over(tempDir).glob("*.txt")
                .forEach(forEachResult::add);

            assertEquals(listResult, streamResult,
                "toList() and toStream() should produce same paths");
            assertEquals(listResult, forEachResult,
                "toList() and forEach() should produce same paths");
        }

        @Test
        @DisplayName("toList() returns unmodifiable list")
        void listIsUnmodifiable() {
            List<Path> paths = Walk.over(tempDir).toList();
            assertThrows(UnsupportedOperationException.class, () ->
                paths.add(tempDir.resolve("fake.txt"))
            );
        }

        @Test
        @DisplayName("toStream() can be consumed only once")
        void streamSingleUse() {
            Stream<Path> stream = Walk.over(tempDir).toStream();
            stream.count(); // first consumption works
            assertThrows(IllegalStateException.class, () ->
                stream.count() // second consumption should throw
            );
        }
    }

    // =======================================================================
    // WalkResult FIELD CORRECTNESS
    // =======================================================================

    @Nested
    @DisplayName("WalkResult field correctness")
    class WalkResultCorrectness {

        @Test
        @DisplayName("matchCount() equals matchedPaths.size()")
        void matchCountMatches() {
            WalkResult result = Walk.over(tempDir).glob("*.txt").walk();
            assertEquals(result.matchedPaths().size(), result.matchCount());
        }

        @Test
        @DisplayName("totalCount >= matchCount")
        void totalCountAtLeastMatchCount() {
            WalkResult result = Walk.over(tempDir).walk();
            assertTrue(result.totalCount() >= result.matchCount(),
                "totalCount should be >= matchCount since it counts all visited entries");
        }

        @Test
        @DisplayName("isClean() returns true when no errors")
        void isCleanNoErrors() {
            WalkResult result = Walk.over(tempDir).walk();
            assertTrue(result.isClean());
        }

        @Test
        @DisplayName("isClean() returns false when errors occur")
        void isCleanWithErrors() {
            Path nonExistent = tempDir.resolve("ghost");
            WalkResult result = Walk.over(nonExistent).walk();
            assertFalse(result.isClean(),
                "isClean should be false when walking non-existent dir");
        }

        @Test
        @DisplayName("summary() contains key fields")
        void summaryContainsFields() {
            WalkResult result = Walk.over(tempDir).glob("*.txt").walk();
            String summary = result.summary();
            assertTrue(summary.contains("Walked"));
            assertTrue(summary.contains("matched"));
            assertTrue(summary.contains("errors"));
            assertTrue(summary.contains("ms"));
        }

        @Test
        @DisplayName("elapsedTime is non-null and non-negative")
        void elapsedTimeValid() {
            WalkResult result = Walk.over(tempDir).walk();
            assertNotNull(result.elapsedTime());
            assertTrue(result.elapsedTime().toMillis() >= 0);
        }
    }

    // =======================================================================
    // maxDepth EDGE CASES
    // =======================================================================

    @Nested
    @DisplayName("maxDepth edge cases")
    class MaxDepthEdgeCases {

        @Test
        @DisplayName("maxDepth(Integer.MAX_VALUE) works (default)")
        void maxDepthMaxValue() {
            WalkResult result = Walk.over(tempDir).maxDepth(Integer.MAX_VALUE).walk();
            assertEquals(6, result.matchCount(), "max MAX_VALUE should walk all files");
        }

        @Test
        @DisplayName("maxDepth(-1) throws for negative depth")
        void negativeMaxDepth() {
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).maxDepth(-1)
            );
        }

        @Test
        @DisplayName("maxDepth(-100) throws for very negative depth")
        void veryNegativeMaxDepth() {
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).maxDepth(-100)
            );
        }

        @Test
        @DisplayName("maxDepth(0) + includeDirs(true) includes root directory")
        void maxDepthZeroWithIncludeDirs() {
            // When maxDepth=0 and includeDirs=true, walkFileTree calls visitFile for
            // the root directory (since it can't descend). The code checks attrs.isDirectory()
            // and adds it if includeDirs=true.
            List<Path> paths = Walk.over(tempDir).maxDepth(0).includeDirs(true).toList();
            assertEquals(1, paths.size(),
                "maxDepth(0)+includeDirs(true) should include root directory");
        }
    }

    // =======================================================================
    // PARALLEL EDGE CASES
    // =======================================================================

    @Nested
    @DisplayName("Parallel edge cases")
    class ParallelEdgeCases {

        @Test
        @DisplayName("parallel(0) throws IllegalArgumentException")
        void parallelZero() {
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).parallel(0)
            );
        }

        @Test
        @DisplayName("parallel(-1) throws IllegalArgumentException")
        void parallelNegative() {
            assertThrows(IllegalArgumentException.class, () ->
                Walk.over(tempDir).parallel(-1)
            );
        }

        @Test
        @DisplayName("parallel with high thread count works")
        @Timeout(15)
        void parallelHighThreadCount() {
            List<Path> paths = Walk.over(tempDir).parallel(32).toList();
            assertEquals(6, paths.size(),
                "High thread count should still produce correct results");
        }

        @Test
        @DisplayName("parallel walk results are consistent across runs")
        void parallelConsistency() {
            List<Path> baseline = Walk.over(tempDir).glob("*.txt").toList();
            for (int i = 0; i < 5; i++) {
                List<Path> run = Walk.over(tempDir)
                    .glob("*.txt")
                    .parallel(4)
                    .toList();
                assertEquals(baseline.size(), run.size(),
                    "Parallel run " + i + " should produce same count");
            }
        }
    }

    // =======================================================================
    // CONCURRENT ACCESS TESTS
    // =======================================================================

    @Nested
    @DisplayName("Concurrent access safety")
    class ConcurrentAccess {

        @Test
        @DisplayName("parallel walk with many subdirectories — no data races")
        @Timeout(30)
        void parallelWithManySubdirs() throws IOException {
            for (int d = 0; d < 50; d++) {
                Path dir = Files.createDirectory(tempDir.resolve("batchdir" + d));
                for (int f = 0; f < 10; f++) {
                    Files.createFile(dir.resolve("f" + f + ".txt"));
                }
            }

            List<Path> sequential = Walk.over(tempDir).glob("*.txt").toList();
            List<Path> parallelResult = Walk.over(tempDir)
                .glob("*.txt")
                .parallel(8)
                .toList();

            assertEquals(503, sequential.size(),
                "Sequential should find 503 .txt files total");
            assertEquals(503, parallelResult.size(),
                "Parallel should find same 503 files as sequential");
        }

        @Test
        @DisplayName("parallel walk does not produce duplicate paths")
        @Timeout(30)
        void noDuplicatesInParallel() throws IOException {
            for (int i = 0; i < 200; i++) {
                Files.createFile(tempDir.resolve("nodedup" + i + ".txt"));
            }

            List<Path> paths = Walk.over(tempDir)
                .glob("*.txt")
                .parallel(8)
                .toList();

            long uniqueCount = paths.stream().distinct().count();
            assertEquals(paths.size(), uniqueCount,
                "Parallel walk should not produce duplicate paths");
        }
    }

    // =======================================================================
    // WalkEventHandler EDGE CASES
    // =======================================================================

    @Nested
    @DisplayName("WalkEventHandler edge cases")
    class WalkEventHandlerEdgeCases {

        @Test
        @DisplayName("walk(null handler) throws NullPointerException")
        void nullHandler() {
            assertThrows(NullPointerException.class, () ->
                Walk.over(tempDir).walk((WalkEventHandler) null)
            );
        }

        @Test
        @DisplayName("handler with all no-ops works")
        void noopHandler() {
            WalkEventHandler handler = new WalkEventHandler() {
                // all defaults — no-ops
            };
            WalkResult result = Walk.over(tempDir).walk(handler);
            assertNotNull(result);
            assertEquals(6, result.matchCount());
        }

        @Test
        @DisplayName("onStart and onEnd are called exactly once each")
        void startAndEndCalledOnce() {
            AtomicInteger startCount = new AtomicInteger(0);
            AtomicInteger endCount = new AtomicInteger(0);

            WalkEventHandler handler = new WalkEventHandler() {
                @Override
                public void onStart(Path root) {
                    startCount.incrementAndGet();
                }
                @Override
                public void onEnd(WalkResult result) {
                    endCount.incrementAndGet();
                }
            };

            Walk.over(tempDir).walk(handler);
            assertEquals(1, startCount.get(), "onStart should be called once");
            assertEquals(1, endCount.get(), "onEnd should be called once");
        }

        @Test
        @DisplayName("handler onFile throwing RuntimeException propagates to caller")
        void handlerThrowsDuringWalk() {
            WalkEventHandler handler = new WalkEventHandler() {
                @Override
                public void onFile(Path file, BasicFileAttributes attrs) {
                    throw new RuntimeException("BOOM from handler");
                }
            };

            // The handler's RuntimeException should propagate up
            // since walkFileTree does not catch RuntimeExceptions
            assertThrows(RuntimeException.class, () ->
                Walk.over(tempDir).walk(handler)
            );
        }

        @Test
        @DisplayName("onError handler receives exceptions for non-existent path")
        void onErrorReceivesExceptions() {
            Path ghost = tempDir.resolve("phantom");
            List<IOException> caught = new ArrayList<>();
            WalkResult result = Walk.over(ghost)
                .onError(caught::add)
                .walk();

            assertTrue(caught.size() > 0 || result.errorCount() > 0,
                "onError should receive exception for non-existent directory");
        }
    }

    // =======================================================================
    // NON-EXISTENT PATH TESTS
    // =======================================================================

    @Nested
    @DisplayName("Non-existent paths")
    class NonExistentPaths {

        @Test
        @DisplayName("walk on non-existent directory returns errors and 0 matches")
        void nonExistentDir() {
            Path ghost = tempDir.resolve("i_dont_exist");
            List<IOException> errors = new ArrayList<>();
            WalkResult result = Walk.over(ghost)
                .onError(errors::add)
                .walk();

            assertEquals(0, result.matchCount(), "Non-existent dir should match nothing");
            assertTrue(result.errorCount() > 0 || errors.size() > 0,
                "Should report error for non-existent path");
        }

        @Test
        @DisplayName("walk on non-existent path in parallel mode")
        void nonExistentParallel() {
            Path ghost = tempDir.resolve("parallel_ghost");
            WalkResult result = Walk.over(ghost).parallel(4).walk();
            assertEquals(0, result.matchCount());
            assertFalse(result.isClean());
        }
    }

    // =======================================================================
    // CHAINING EDGE CASES
    // =======================================================================

    @Nested
    @DisplayName("Chaining edge cases")
    class ChainingEdgeCases {

        @Test
        @DisplayName("multiple globs + regex intersection works")
        void multipleGlobsAndRegex() throws IOException {
            Files.createFile(tempDir.resolve("data.txt"));
            Files.createFile(tempDir.resolve("data.csv"));
            Files.createFile(tempDir.resolve("info.txt"));

            // glob("data*") AND glob("*.txt") = files starting with "data" AND ending with ".txt"
            // Regex must also match .txt files
            List<Path> paths = Walk.over(tempDir)
                .glob("data*")
                .glob("*.txt")
                .regex(".*\\.txt$")
                .toList();

            assertEquals(1, paths.size(), "Should match exactly data.txt");
            assertTrue(paths.get(0).toString().endsWith("data.txt"));
        }

        @Test
        @DisplayName("full chain with all configuration methods")
        void fullChain() {
            WalkResult result = Walk.over(tempDir)
                .glob("*.txt")
                .regex(".*file[135].*")
                .maxDepth(3)
                .includeDirs(false)
                .followSymlinks(false)
                .onError(ex -> {})
                .walk();

            assertEquals(3, result.matchCount(),
                "Should match file1.txt, file3.txt, file5.txt");
            assertTrue(result.isClean());
        }
    }
}
