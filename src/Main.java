package src;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.stream.Stream;

public class Main {

    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB
    private static long totalLoadedBytes = 0;
    private static long totalTargetBytes = 0;
    // 前回表示時の進捗（0.1%単位で管理）
    private static int lastReportedProgress = -1;

    public static void main(String[] args) {
        Path rootPath = Paths.get("D:\\");

        System.out.println("[INFO] Step 1: Calculating total size...");
        try (Stream<Path> paths = Files.walk(rootPath)) {
            totalTargetBytes = paths.filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().length())
                    .sum();
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to scan directory: " + e.getMessage());
            return;
        }

        double totalGB = totalTargetBytes / (1024.0 * 1024 * 1024);
        System.out.printf("[INFO] Total target size: %.2f GB%n", totalGB);

        System.out.print("Do you want to start loading? (y/n): ");
        try (Scanner scanner = new Scanner(System.in)) {
            if (!scanner.nextLine().equalsIgnoreCase("y")) {
                System.out.println("Aborted.");
                return;
            }
        }

        System.out.println("[INFO] Starting execution (Sequential, Append-mode)...");

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile).forEach(Main::loadFile);
        } catch (IOException e) {
            System.err.println("[ERROR] Error during execution: " + e.getMessage());
        }

        System.out.printf("%n[FINISH] All memory loading completed. Total loaded: %.2f MB%n",
                totalLoadedBytes / (1024.0 * 1024));
    }

    private static final byte[] buffer = new byte[BUFFER_SIZE];

    private static void loadFile(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            int n;
            while ((n = is.read(buffer)) != -1) {
                totalLoadedBytes += n;

                // 巨大なファイルの場合、読み込みの途中でも進捗を出す
                reportProgress(path, false);
            }

            // ファイル読み込み完了時の最終報告
            reportProgress(path, true);

        } catch (IOException e) {
            System.err.printf("[SKIP] Error: %s (%s)%n", path.getFileName(), e.getMessage());
        }
    }

    private static void reportProgress(Path path, boolean isComplete) {
        double rawProgress = (totalTargetBytes > 0)
                ? ((double) totalLoadedBytes / totalTargetBytes * 100)
                : 100.0;
        int currentProgressInt = (int) (rawProgress * 10);

        // 0.1%進んだか、あるいはファイルが完了した時に表示
        if (currentProgressInt > lastReportedProgress || isComplete) {
            System.out.printf("[%5.1f%%] %s: %s%n",
                    rawProgress,
                    isComplete ? "Loaded" : "Loading",
                    path.getFileName());
            lastReportedProgress = currentProgressInt;
        }
    }
}