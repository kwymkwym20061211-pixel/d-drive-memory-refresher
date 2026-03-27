package src;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

/**
 * Dドライブの全ファイルを再帰的にロードし、メモリの揮発を防止するためのクラス。
 */
public class Main {

    private static final int THREAD_COUNT = 4;
    private static final int BUFFER_SIZE = 8 * 1024 * 1024;
    private static final LongAdder totalLoadedBytes = new LongAdder();
    // 全体サイズを保持する変数
    private static long totalTargetBytes = 0;

    public static void main(String[] args) {
        Path rootPath = Paths.get("D:\\");

        System.out.println("Step 1: Calculating total size...");

        // 1. 最初に全体のサイズを求める
        try (Stream<Path> paths = Files.walk(rootPath)) {
            totalTargetBytes = paths.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        } catch (IOException e) {
            System.err.println("Failed to scan directory: " + e.getMessage());
            return;
        }

        double totalGB = totalTargetBytes / (1024.0 * 1024 * 1024);
        System.out.printf("Total target size: %.2f GB%n", totalGB);

        // 2. 実行しますか？(y/n)
        System.out.print("Do you want to start loading? (y/n): ");
        @SuppressWarnings("resource")
        Scanner scanner = new Scanner(System.in);
        String input = scanner.nextLine();

        if (!input.equalsIgnoreCase("y")) {
            System.out.println("Aborted.");
            return;
        }

        // 3. 実行開始
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        System.out.println("Starting execution...");

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                executor.submit(() -> loadFile(path));
            });
        } catch (IOException e) {
            System.err.println("Error during submission: " + e.getMessage());
        }

        executor.shutdown();
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        long totalMB = totalLoadedBytes.sum() / (1024 * 1024);
        System.out.println("\nAll memory loading completed. Total loaded: " + totalMB + " MB");
    }

    private static void loadFile(Path path) {
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream is = Files.newInputStream(path)) {
            int n;
            while ((n = is.read(buffer)) != -1) {
                totalLoadedBytes.add(n);
                // 進捗計算は全体に対する割合で出すため、ここでは加算のみ
            }

            // 進捗%（小数点第一位）を計算
            double progress = (totalTargetBytes > 0)
                    ? (totalLoadedBytes.doubleValue() / totalTargetBytes * 100)
                    : 100.0;

            System.out.printf("[%5.1f%%] Loaded: %s%n", progress, path.getFileName());

        } catch (IOException e) {
            System.err.println("Skip (Error): " + path + " - " + e.getMessage());
        }
    }
}