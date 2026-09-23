package src;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ディレクトリ配下の各ディレクトリについて、
 * そのディレクトリ直下に存在する通常ファイルをまとめた
 * SHA-256 ハッシュを .hash として保存・照合する。
 *
 * .hash は32バイト固定長のバイナリファイル。
 *
 * 例:
 *
 * root/
 * ├── a.txt
 * ├── b.dat
 * ├── .hash
 * ├── dir1/
 * │ ├── c.txt
 * │ ├── d.bin
 * │ └── .hash
 * └── dir2/
 * └── .hash
 *
 * 各 .hash は「そのディレクトリ直下の通常ファイル」だけを
 * 対象とする。
 *
 * サブディレクトリの内容は、その親ディレクトリのハッシュには
 * 含まれない。
 */
public final class DirectoryHasher {

    /**
     * ハッシュファイル名。
     */
    private static final String HASH_FILE_NAME = ".hash";

    /**
     * 使用するハッシュアルゴリズム。
     *
     * SHA-256 は出力が必ず32バイトなので、
     * .hash を固定長にできる。
     */
    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * ファイル内容を読む際のバッファサイズ。
     */
    private static final int BUFFER_SIZE = 1024 * 1024; // 1 MiB

    private DirectoryHasher() {
        // utility class
    }

    /**
     * 指定ディレクトリ配下を再帰的に走査し、
     * 各ディレクトリの直下に存在する通常ファイルをまとめた
     * SHA-256 ハッシュを、そのディレクトリの .hash に保存する。
     *
     * .hash 自身はハッシュ対象から除外する。
     *
     * 既存の .hash は上書きされる。
     *
     * @param root 走査対象ディレクトリ
     * @throws IOException ファイル操作・ハッシュ生成に失敗した場合
     */
    public static void createHashes(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();

        validateDirectory(root);

        /*
         * ディレクトリを再帰的に取得する。
         *
         * post-order にしているが、今回のハッシュは
         * サブディレクトリを含まないため順序自体は意味を持たない。
         */
        List<Path> directories;

        try (Stream<Path> stream = Files.walk(root)) {
            directories = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        for (Path directory : directories) {
            byte[] hash = calculateDirectoryHash(directory);

            Path hashFile = directory.resolve(HASH_FILE_NAME);

            Files.write(
                    hashFile,
                    hash);

            System.out.println(
                    "Created: " + hashFile);
        }
    }

    /**
     * 指定ディレクトリ配下を再帰的に走査し、
     * 各ディレクトリについて既存の .hash と現在の内容を照合する。
     *
     * .hash が存在しない場合もエラーとして扱う。
     *
     * ハッシュが一致しないディレクトリはすべて記録し、
     * 最後に例外を throw する。
     *
     * 呼び出し元が例外を catch することを前提とし、
     * このメソッド自身ではエラー処理を行わない。
     *
     * @param root 走査対象ディレクトリ
     * @throws IOException .hash がない、読み取り不能、内容不一致などの場合
     */
    public static void verifyHashes(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();

        validateDirectory(root);

        List<String> errors = new ArrayList<>();

        List<Path> directories;

        try (Stream<Path> stream = Files.walk(root)) {
            directories = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        for (Path directory : directories) {

            Path hashFile = directory.resolve(HASH_FILE_NAME);

            /*
             * .hash が存在しないこと自体をエラーにする。
             */
            if (!Files.isRegularFile(hashFile)) {
                errors.add(
                        "HASH FILE MISSING: " + hashFile);
                continue;
            }

            /*
             * .hash は SHA-256 固定長32バイトであるべき。
             */
            long hashFileSize = Files.size(hashFile);

            if (hashFileSize != 32) {
                errors.add(
                        "INVALID HASH FILE SIZE: "
                                + hashFile
                                + " (expected=32, actual="
                                + hashFileSize
                                + ")");
                continue;
            }

            byte[] expectedHash;

            try {
                expectedHash = Files.readAllBytes(hashFile);
            } catch (IOException e) {
                errors.add(
                        "HASH FILE READ FAILED: "
                                + hashFile
                                + " : "
                                + e.getMessage());
                continue;
            }

            byte[] actualHash;

            try {
                actualHash = calculateDirectoryHash(directory);
            } catch (IOException e) {
                errors.add(
                        "HASH CALCULATION FAILED: "
                                + directory
                                + " : "
                                + e.getMessage());
                continue;
            }

            if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                errors.add(
                        "HASH MISMATCH: "
                                + directory
                                + System.lineSeparator()
                                + "  expected: "
                                + toHex(expectedHash)
                                + System.lineSeparator()
                                + "  actual  : "
                                + toHex(actualHash));
            }
        }

        /*
         * 最後にまとめて throw。
         *
         * 途中で1件見つかった時点では止めず、
         * 配下の不一致を可能な限り全部列挙する。
         */
        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder();

            message.append(
                    "Directory hash verification failed.");

            message.append(
                    System.lineSeparator());

            message.append(
                    "Detected ").append(errors.size())
                    .append(" problem(s):")
                    .append(System.lineSeparator());

            for (String error : errors) {
                message.append(
                        "  - ").append(error)
                        .append(System.lineSeparator());
            }

            throw new IOException(message.toString());
        }

        System.out.println(
                "Hash verification successful: " + root);
    }

    /**
     * 1つのディレクトリについて、
     * 直下の通常ファイルすべてをまとめた SHA-256 を計算する。
     *
     * サブディレクトリは対象外。
     *
     * ハッシュへの入力は以下の形式。
     *
     * [ファイル名の長さ]
     * [ファイル名]
     * [ファイルサイズ]
     * [ファイル内容]
     *
     * これをファイル名順に連結して SHA-256 を計算する。
     *
     * 長さを明示的に入れることで、
     * 単純な文字列連結による曖昧性を避ける。
     */
    private static byte[] calculateDirectoryHash(
            Path directory) throws IOException {

        List<Path> files;

        try (Stream<Path> stream = Files.list(directory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName()
                            .toString()
                            .equals(HASH_FILE_NAME))
                    .sorted(
                            Comparator.comparing(
                                    path -> path.getFileName().toString()))
                    .toList();
        }

        MessageDigest digest = createDigest();

        /*
         * DataOutputStream を使って、
         * ファイル名やサイズを明確なバイナリ形式で
         * ハッシュ入力にする。
         *
         * DataOutputStream は OutputStream に対して
         * writeInt / writeLong 等を提供する。
         *
         * ただし実際にファイルへ書き込むのではなく、
         * DigestOutputStream 等は使わず、
         * byte[] を直接 digest.update() する。
         */
        for (Path file : files) {

            String fileName = file.getFileName().toString();

            byte[] fileNameBytes = fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            /*
             * ファイル名長
             */
            updateInt(digest, fileNameBytes.length);

            /*
             * ファイル名
             */
            digest.update(fileNameBytes);

            /*
             * ファイルサイズ
             *
             * 内容だけでなくサイズも入れる。
             */
            long fileSize = Files.size(file);

            updateLong(digest, fileSize);

            /*
             * ファイル内容
             */
            updateFileContent(digest, file);
        }

        return digest.digest();
    }

    /**
     * 指定されたファイルの内容を digest に投入する。
     */
    private static void updateFileContent(
            MessageDigest digest,
            Path file) throws IOException {

        byte[] buffer = new byte[BUFFER_SIZE];

        try (
                InputStream in = new BufferedInputStream(
                        Files.newInputStream(file),
                        BUFFER_SIZE)) {
            while (true) {
                int read = in.read(buffer);

                if (read == -1) {
                    break;
                }

                digest.update(buffer, 0, read);
            }
        }
    }

    /**
     * int をビッグエンディアンで digest に投入する。
     */
    private static void updateInt(
            MessageDigest digest,
            int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    /**
     * long をビッグエンディアンで digest に投入する。
     */
    private static void updateLong(
            MessageDigest digest,
            long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    /**
     * SHA-256 の MessageDigest を作成する。
     */
    private static MessageDigest createDigest() {

        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);

        } catch (NoSuchAlgorithmException e) {
            /*
             * Java 標準実装では SHA-256 は必須なので、
             * 通常この例外は発生しない。
             */
            throw new IllegalStateException(
                    "SHA-256 is not available.",
                    e);
        }
    }

    /**
     * 指定されたパスがディレクトリであることを確認する。
     */
    private static void validateDirectory(Path directory) {

        if (!Files.exists(directory)) {
            throw new IllegalArgumentException(
                    "指定されたディレクトリが存在しません: "
                            + directory);
        }

        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException(
                    "指定されたパスはディレクトリではありません: "
                            + directory);
        }
    }

    /**
     * ハッシュを16進文字列に変換する。
     *
     * エラーメッセージ表示専用。
     */
    private static String toHex(byte[] bytes) {

        StringBuilder result = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            result.append(
                    String.format("%02x", b & 0xff));
        }

        return result.toString();
    }
}
