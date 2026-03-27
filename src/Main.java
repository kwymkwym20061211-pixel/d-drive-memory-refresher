package src;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

/**
 * Dドライブの全ファイルを再帰的にロードし、メモリの揮発を防止するためのクラス。
 * 読み込んだデータは保持せず、即座に破棄される。
 */
public class Main {

    // スレッド数はCPUコア数やディスクI/O特性に合わせる（4~8程度が現実的）
    private static final int THREAD_COUNT = 4;
    // 1回の読み込みバッファサイズ（1MB）
    private static final int BUFFER_SIZE = 1024 * 1024;
    // 進捗管理用（スレッドセーフな加算器）
    private static final LongAdder totalLoadedBytes = new LongAdder();

    public static void main(String[] args) {
        Path rootPath = Paths.get("D:\\");
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        System.out.println("Scanning and loading files from: " + rootPath);

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                executor.submit(() -> loadFile(path));
            });
        } catch (IOException e) {
            System.err.println("Failed to access path: " + e.getMessage());
        }

        // 全タスクの終了を待機
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        long totalMB = totalLoadedBytes.sum() / (1024 * 1024);
        System.out.println("\nAll memory loading completed. Total loaded: " + totalMB + " MB");
    }

    /**
     * 指定されたファイルをバッファサイズごとに読み込む
     */
    private static void loadFile(Path path) {
        // 巨大ファイルでもOOMにならないよう、固定長バッファで読み込む
        byte[] buffer = new byte[BUFFER_SIZE];
        long fileLoadedBytes = 0;

        try (InputStream is = Files.newInputStream(path)) {
            int n;
            while ((n = is.read(buffer)) != -1) {
                // 読み込むこと自体が目的なので、中身には触れない
                // 読み込んだバイト数を加算
                totalLoadedBytes.add(n);
                fileLoadedBytes += n;
            }

            // 進捗表示（コンソールが埋まらないよう、ファイル単位程度で出すのが現実的）
            System.out.printf("Loaded: %s (%d MB)%n",
                    path.getFileName(), fileLoadedBytes / (1024 * 1024));

        } catch (IOException e) {
            // システムファイルなどアクセス権限がないものはスキップ
            System.err.println("Skip (Access Denied/Error): " + path + " - " + e.getMessage());
        }
    }
}
