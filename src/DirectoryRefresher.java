package src;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * 指定されたディレクトリ直下の A/ と B/ のうち、
 * 中身のある側を空の側へコピーして更新する。
 *
 * 前提:
 * parent/
 * ├── A/
 * └── B/
 *
 * A/B 以外の直下エントリは存在してはいけない。
 * A/B の一方だけが空でなければならない。
 */
public final class DirectoryRefresher {

    private DirectoryRefresher() {
        // utility class
    }

    /**
     * データのリフレッシュを実行する。
     *
     * @param parent A/ と B/ の直接の親ディレクトリ
     * @throws IOException
     * @throws IllegalStateException 前提条件違反
     */
    public static void refresh(Path parent) throws IOException {
        parent = parent.toAbsolutePath().normalize();

        System.out.println("=== Directory Refresh ===");
        System.out.println("Parent : " + parent);

        validateParent(parent);

        Path a = parent.resolve("A");
        Path b = parent.resolve("B");

        boolean aEmpty = isEmptyDirectory(a);
        boolean bEmpty = isEmptyDirectory(b);

        if (aEmpty == bEmpty) {
            throw new IllegalStateException(
                    "A/ と B/ のどちらか一方だけが空である必要があります。"
                            + " A empty=" + aEmpty
                            + ", B empty=" + bEmpty);
        }

        Path source;
        Path target;

        if (!aEmpty) {
            source = a;
            target = b;
        } else {
            source = b;
            target = a;
        }

        System.out.println("Source : " + source);
        System.out.println("Target : " + target);

        // ------------------------------------------------------------
        // 1. コピー対象ファイルを完全に走査して一覧化
        // ------------------------------------------------------------

        System.out.println();
        System.out.println("[1/7] Scanning source files...");

        List<Path> files = collectFiles(source);

        if (files.isEmpty()) {
            throw new IllegalStateException(
                    "source が空ではありませんが、通常ファイルが1つもありません: "
                            + source);
        }

        long largestFileSize = files.stream()
                .mapToLong(DirectoryRefresher::fileSizeUnchecked)
                .max()
                .orElse(0L);

        System.out.println("Files        : " + files.size());
        System.out.println("Largest file : " + formatBytes(largestFileSize));

        // ------------------------------------------------------------
        // 1'. 空き容量チェック
        // ------------------------------------------------------------

        FileStore fileStore = Files.getFileStore(parent);
        long usableSpace = fileStore.getUsableSpace();

        System.out.println("Usable space : " + formatBytes(usableSpace));
        System.out.println(
                "Required max : " + formatBytes(largestFileSize));
        System.out.println(
                "Safety limit : " + formatBytes(usableSpace / 2));

        if (largestFileSize > usableSpace / 2) {
            throw new IllegalStateException(
                    "最大ファイルサイズがドライブ空き容量の 1/2 を超えています。\n"
                            + "  Largest file : " + largestFileSize + " bytes\n"
                            + "  Usable space : " + usableSpace + " bytes\n"
                            + "  Half         : " + (usableSpace / 2) + " bytes");
        }

        // ------------------------------------------------------------
        // 2. ディレクトリ構造だけを先に作る
        // ------------------------------------------------------------

        System.out.println();
        System.out.println("[2/7] Recreating directory structure...");

        recreateDirectoryTree(source, target);

        // ------------------------------------------------------------
        // 3. もう一度 source を走査してファイルを確定
        // ------------------------------------------------------------

        System.out.println();
        System.out.println("[3/7] Re-scanning source files...");

        List<Path> finalFiles = collectFiles(source);

        if (finalFiles.size() != files.size()) {
            throw new IllegalStateException(
                    "事前走査と再走査でファイル数が変化しました。\n"
                            + "  First scan : " + files.size() + "\n"
                            + "  Second scan: " + finalFiles.size());
        }

        // ------------------------------------------------------------
        // 4. 1ファイルずつコピー → 検証 → 元ファイル削除
        // ------------------------------------------------------------

        System.out.println();
        System.out.println("[4/7] Copying files...");

        for (int i = 0; i < finalFiles.size(); i++) {
            Path sourceFile = finalFiles.get(i);
            Path relative = source.relativize(sourceFile);
            Path targetFile = target.resolve(relative);

            copyVerifyAndDelete(
                    sourceFile,
                    targetFile,
                    i + 1,
                    finalFiles.size());
        }

        // ------------------------------------------------------------
        // 5. コピー元にファイルが残っていないことを確認
        // ------------------------------------------------------------

        System.out.println();
        System.out.println("[5/7] Verifying source contains no files...");

        verifyNoFilesRemain(source);

        // ------------------------------------------------------------
        // 6. コピー元の空ディレクトリをすべて削除
        // ------------------------------------------------------------

        System.out.println("[6/7] Removing empty source directories...");

        removeEmptyDirectories(source);

        // ------------------------------------------------------------
        // 7. 最終確認
        // ------------------------------------------------------------

        System.out.println("[7/7] Performing final emptiness check...");

        verifyCompletelyEmpty(source);

        System.out.println();
        System.out.println("=== Refresh completed successfully ===");
        System.out.println("Source: " + source + " (completely empty)");
        System.out.println("Target: " + target + " (contains refreshed data)");

    }

    /**
     * parent の直下が A/ と B/ の2つだけであることを確認する。
     */
    private static void validateParent(Path parent) throws IOException {
        if (!Files.exists(parent)) {
            throw new IllegalArgumentException(
                    "指定された親ディレクトリが存在しません: " + parent);
        }

        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException(
                    "指定されたパスはディレクトリではありません: " + parent);
        }

        List<Path> children;

        try (Stream<Path> stream = Files.list(parent)) {
            children = stream.toList();
        }

        if (children.size() != 2) {
            throw new IllegalStateException(
                    "親ディレクトリ直下には A/ と B/ の2つだけが存在する必要があります。\n"
                            + "実際のエントリ数: " + children.size()
                            + "\n" + children);
        }

        Path a = parent.resolve("A");
        Path b = parent.resolve("B");

        if (!Files.isDirectory(a)) {
            throw new IllegalStateException(
                    "A/ が存在しないか、ディレクトリではありません: " + a);
        }

        if (!Files.isDirectory(b)) {
            throw new IllegalStateException(
                    "B/ が存在しないか、ディレクトリではありません: " + b);
        }

        for (Path child : children) {
            String name = child.getFileName().toString();

            if (!name.equals("A") && !name.equals("B")) {
                throw new IllegalStateException(
                        "A/ と B/ 以外のエントリが存在します: " + child);
            }
        }
    }

    /**
     * ディレクトリが完全に空か判定する。
     */
    private static boolean isEmptyDirectory(Path directory)
            throws IOException {

        try (Stream<Path> stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        }
    }

    /**
     * 通常ファイルを再帰的に列挙する。
     *
     * シンボリックリンクは安全のため拒否する。
     */
    private static List<Path> collectFiles(Path root)
            throws IOException {

        List<Path> result = new ArrayList<>();

        Files.walkFileTree(
                root,
                EnumSet.noneOf(FileVisitOption.class),
                Integer.MAX_VALUE,
                new java.nio.file.SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attrs) throws IOException {

                        if (Files.isSymbolicLink(file)) {
                            throw new IOException(
                                    "シンボリックリンクはサポートしていません: "
                                            + file);
                        }

                        if (!attrs.isRegularFile()) {
                            throw new IOException(
                                    "通常ファイルではないエントリがあります: "
                                            + file);
                        }

                        result.add(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(
                            Path file,
                            IOException exc) throws IOException {

                        throw new IOException(
                                "ファイルの走査に失敗しました: " + file,
                                exc);
                    }
                });

        /*
         * 順番を固定する。
         * これにより、実行ごとのコピー順序が安定する。
         */
        result.sort(Comparator.comparing(Path::toString));

        return result;
    }

    /**
     * source のディレクトリ構造だけを target に再現する。
     */
    private static void recreateDirectoryTree(
            Path source,
            Path target) throws IOException {

        Files.walkFileTree(
                source,
                EnumSet.noneOf(FileVisitOption.class),
                Integer.MAX_VALUE,
                new java.nio.file.SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path dir,
                            BasicFileAttributes attrs) throws IOException {

                        Path relative = source.relativize(dir);
                        Path targetDir = target.resolve(relative);

                        Files.createDirectories(targetDir);

                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /**
     * 1ファイルを
     *
     * コピー
     * ↓
     * サイズ確認
     * ↓
     * 全バイト比較
     * ↓
     * 成功なら元を削除
     *
     * の順に処理する。
     */
    private static void copyVerifyAndDelete(
            Path source,
            Path target,
            int index,
            int total) throws IOException {

        long sourceSize = Files.size(source);

        System.out.printf(
                "[%d/%d] %s (%s)%n",
                index,
                total,
                source,
                formatBytes(sourceSize));

        /*
         * target の親ディレクトリが存在することを保証する。
         *
         * target 自体はまだ存在しないので、
         * Files.getFileStore(target) は呼び出してはいけない。
         */
        Path targetParent = target.getParent();

        if (targetParent == null) {
            throw new IOException(
                    "コピー先ファイルの親ディレクトリを取得できません。\n"
                            + "Target: " + target);
        }

        Files.createDirectories(targetParent);

        /*
         * コピー直前にも空き容量を確認する。
         *
         * target はまだ存在しないため、
         * FileStore は既に存在する targetParent から取得する。
         */
        FileStore store = Files.getFileStore(targetParent);
        long usable = store.getUsableSpace();

        if (sourceSize > usable) {
            throw new IOException(
                    "コピー先の空き容量が不足しています。\n"
                            + "  Source      : " + source + "\n"
                            + "  Target      : " + target + "\n"
                            + "  File size   : " + sourceSize + " bytes\n"
                            + "  Usable space: " + usable + " bytes");
        }

        // ------------------------------------------------------------
        // コピー
        // ------------------------------------------------------------

        try {
            Files.copy(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IOException(
                    "ファイルのコピーに失敗しました。\n"
                            + "  Source: " + source + "\n"
                            + "  Target: " + target + "\n"
                            + "  Size  : " + sourceSize + " bytes",
                    e);
        }

        // ------------------------------------------------------------
        // サイズ確認
        // ------------------------------------------------------------

        long targetSize;

        try {
            targetSize = Files.size(target);
        } catch (IOException e) {
            throw new IOException(
                    "コピー先ファイルのサイズ取得に失敗しました。\n"
                            + "  Target: " + target,
                    e);
        }

        if (sourceSize != targetSize) {
            throw new IOException(
                    "コピー後のサイズが一致しません。\n"
                            + "  Source: " + source + "\n"
                            + "  Target: " + target + "\n"
                            + "  Source size: " + sourceSize + "\n"
                            + "  Target size: " + targetSize);
        }

        // ------------------------------------------------------------
        // 全バイト比較
        // ------------------------------------------------------------

        boolean identical;

        try {
            identical = filesAreIdentical(source, target);
        } catch (IOException e) {
            throw new IOException(
                    "コピー後の内容比較に失敗しました。\n"
                            + "  Source: " + source + "\n"
                            + "  Target: " + target,
                    e);
        }

        if (!identical) {
            throw new IOException(
                    "コピー後の内容が一致しません。\n"
                            + "  Source: " + source + "\n"
                            + "  Target: " + target + "\n"
                            + "  Source size: " + sourceSize + "\n"
                            + "  Target size: " + targetSize);
        }

        // ------------------------------------------------------------
        // 完全一致したので元を削除
        // ------------------------------------------------------------

        try {
            Files.delete(source);
        } catch (IOException e) {
            throw new IOException(
                    "コピーと検証は成功しましたが、コピー元の削除に失敗しました。\n"
                            + "  Source: " + source + "\n"
                            + "  Target: " + target + "\n"
                            + "  WARNING: 両方にファイルが存在する状態です。",
                    e);
        }

        System.out.println("  -> verified & deleted");
    }

    /**
     * 2ファイルを完全にバイト単位で比較する。
     */
    private static boolean filesAreIdentical(
            Path a,
            Path b) throws IOException {

        if (Files.size(a) != Files.size(b)) {
            return false;
        }

        /*
         * 1 MiB バッファ。
         *
         * サイズが大きいファイルでも、ファイル全体を
         * byte[] に載せることはしない。
         */
        byte[] bufferA = new byte[1024 * 1024];
        byte[] bufferB = new byte[1024 * 1024];

        try (
                InputStream inA = new BufferedInputStream(Files.newInputStream(a), bufferA.length);

                InputStream inB = new BufferedInputStream(Files.newInputStream(b), bufferB.length)) {
            while (true) {
                int readA = readFully(inA, bufferA);
                int readB = readFully(inB, bufferB);

                if (readA != readB) {
                    return false;
                }

                if (readA == -1) {
                    return true;
                }

                for (int i = 0; i < readA; i++) {
                    if (bufferA[i] != bufferB[i]) {
                        return false;
                    }
                }
            }
        }
    }

    /**
     * バッファを可能な限り埋める。
     */
    private static int readFully(
            InputStream in,
            byte[] buffer) throws IOException {

        int total = 0;

        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);

            if (read == -1) {
                break;
            }

            total += read;
        }

        return total == 0 ? -1 : total;
    }

    /**
     * IOException を投げずにファイルサイズを取得するための補助。
     */
    private static long fileSizeUnchecked(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new RuntimeException(
                    "ファイルサイズの取得に失敗しました: " + path,
                    e);
        }
    }

    /**
     * バイト数を人間が読みやすい形式にする。
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes;
        String[] units = {
                "KiB",
                "MiB",
                "GiB",
                "TiB",
                "PiB"
        };

        for (String unit : units) {
            value /= 1024.0;

            if (value < 1024.0) {
                return String.format("%.2f %s", value, unit);
            }
        }

        return String.format("%.2f EiB", value);
    }

    /**
     * source 配下にファイルが残っていないことを確認する。
     *
     * 空ディレクトリはこの時点では許容する。
     */
    private static void verifyNoFilesRemain(Path source)
            throws IOException {

        List<Path> remainingFiles = new ArrayList<>();

        Files.walkFileTree(
                source,
                EnumSet.noneOf(FileVisitOption.class),
                Integer.MAX_VALUE,
                new java.nio.file.SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attrs) throws IOException {

                        remainingFiles.add(file);

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(
                            Path file,
                            IOException exc) throws IOException {

                        throw new IOException(
                                "コピー元の最終確認に失敗しました: "
                                        + file,
                                exc);
                    }
                });

        if (!remainingFiles.isEmpty()) {
            StringBuilder message = new StringBuilder();

            message.append(
                    "コピー元にファイルが残っています。"
                            + " 空ディレクトリの削除には進みません。\n");

            message.append(
                    "残存ファイル数: ").append(remainingFiles.size()).append("\n");

            for (Path file : remainingFiles) {
                message.append("  ").append(file).append("\n");
            }

            throw new IOException(message.toString());
        }

        System.out.println(
                "  -> No files remain in source.");
    }

    /**
     * source 配下の空ディレクトリをすべて削除する。
     *
     * 深いディレクトリから順番に削除する。
     */
    private static void removeEmptyDirectories(Path source)
            throws IOException {

        List<Path> directories;

        try (Stream<Path> stream = Files.walk(source)) {
            directories = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(source))
                    .sorted(
                            Comparator
                                    .comparingInt(Path::getNameCount)
                                    .reversed())
                    .toList();
        }

        for (Path directory : directories) {

            try {
                Files.delete(directory);

                System.out.println(
                        "  deleted directory: " + directory);

            } catch (DirectoryNotEmptyException e) {
                throw new IOException(
                        "ディレクトリが空ではありません。"
                                + "安全のため処理を中断します。\n"
                                + "Directory: " + directory,
                        e);
            }
        }
    }

    /**
     * source 配下にファイル・ディレクトリが一切残っていないことを確認する。
     *
     * source 自体は当然残っていてよい。
     */
    private static void verifyCompletelyEmpty(Path source)
            throws IOException {

        try (Stream<Path> stream = Files.list(source)) {

            List<Path> remaining = stream.toList();

            if (!remaining.isEmpty()) {

                StringBuilder message = new StringBuilder();

                message.append(
                        "コピー元ディレクトリが完全には空になっていません。\n");

                message.append(
                        "残存エントリ数: ").append(remaining.size()).append("\n");

                for (Path path : remaining) {
                    message.append(
                            "  ").append(path).append("\n");
                }

                throw new IOException(message.toString());
            }
        }

        System.out.println(
                "  -> Source is completely empty.");
    }
}