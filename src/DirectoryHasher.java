package src;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DirectoryHasher {

    private static final String HASH_FILE_NAME = ".hash.toml";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 1024 * 1024;

    private static final String HEADER = "# SHA-256 hashes for verifying the integrity of files in this directory tree.";

    private DirectoryHasher() {
    }

    /**
     * 指定ディレクトリ以下のファイルについてハッシュ情報を生成し、
     * root/.hash.toml に保存する。
     */
    public static void createHash(Path root) throws IOException {
        root = normalizeRoot(root);

        Map<String, List<FileHash>> entries = new LinkedHashMapCompat<>();
        Set<Path> activeDirectories = new HashSet<>();

        collectFiles(root, root, entries, activeDirectories);

        Path hashFile = root.resolve(HASH_FILE_NAME);

        /*
         * 一時ファイルに書き出してから置き換える。
         * 書き込み途中で異常終了しても、既存の .hash.toml を壊しにくくする。
         */
        Path temporaryFile = Files.createTempFile(
                root,
                HASH_FILE_NAME + ".",
                ".tmp");

        boolean success = false;

        try {
            writeHashFile(temporaryFile, entries);

            try {
                Files.move(
                        temporaryFile,
                        hashFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryFile,
                        hashFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            success = true;
        } finally {
            if (!success) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    /**
     * root/.hash.toml と現在のファイルシステムを照合する。
     *
     * 不一致・追加・削除・破損などが存在する場合、
     * 可能な限り全件を検査した後 IOException を送出する。
     */
    public static void verifyHash(Path root) throws IOException {
        root = normalizeRoot(root);

        Path hashFile = root.resolve(HASH_FILE_NAME);

        if (!Files.isRegularFile(hashFile)) {
            throw new IOException(
                    "Hash file does not exist or is not a regular file: "
                            + hashFile);
        }

        Map<String, List<FileHash>> expected;

        try {
            expected = readHashFile(hashFile);
        } catch (IOException e) {
            throw new IOException(
                    "Failed to read hash file: " + hashFile,
                    e);
        }

        List<String> errors = new ArrayList<>();
        Set<String> actualPaths = new HashSet<>();
        Set<Path> activeDirectories = new HashSet<>();

        try {
            verifyDirectory(
                    root,
                    root,
                    expected,
                    actualPaths,
                    activeDirectories,
                    errors);
        } catch (IOException e) {
            errors.add("Failed while scanning directory tree: " + e.getMessage());
        }

        /*
         * hash.toml にだけ存在するファイルを検出する。
         */
        for (Map.Entry<String, List<FileHash>> directoryEntry : expected.entrySet()) {
            String directory = directoryEntry.getKey();

            for (FileHash expectedFile : directoryEntry.getValue()) {
                String relativePath = combineRelativePath(
                        directory,
                        expectedFile.fileName);

                if (!actualPaths.contains(relativePath)) {
                    errors.add("Missing file: " + relativePath);
                }
            }
        }

        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append("Hash verification failed: ")
                    .append(errors.size())
                    .append(" problem(s).\n");

            for (String error : errors) {
                message.append("  ")
                        .append(error)
                        .append('\n');
            }

            throw new IOException(message.toString());
        }
    }

    // -------------------------------------------------------------------------
    // ファイル収集
    // -------------------------------------------------------------------------

    private static void collectFiles(
            Path root,
            Path directory,
            Map<String, List<FileHash>> entries,
            Set<Path> activeDirectories) throws IOException {

        Path realDirectory = directory.toRealPath();

        if (!activeDirectories.add(realDirectory)) {
            throw new IOException(
                    "Symbolic-link directory cycle detected: " + directory);
        }

        try {
            List<FileHash> directFiles = new ArrayList<>();

            try (var stream = Files.list(directory)) {
                var iterator = stream.iterator();

                while (iterator.hasNext()) {
                    Path path = iterator.next();

                    if (isHashFile(root, path)) {
                        continue;
                    }

                    if (Files.isRegularFile(path)) {
                        String fileName = path.getFileName().toString();

                        String relativeDirectory = toPortableRelativeDirectory(root, directory);

                        byte[] hash = calculateFileHash(path);

                        directFiles.add(
                                new FileHash(fileName, hash));
                    } else if (Files.isDirectory(path)) {
                        collectFiles(
                                root,
                                path,
                                entries,
                                activeDirectories);
                    }
                }
            }

            if (!directFiles.isEmpty()) {
                String relativeDirectory = toPortableRelativeDirectory(root, directory);

                entries.put(relativeDirectory, directFiles);
            }
        } finally {
            activeDirectories.remove(realDirectory);
        }
    }

    private static void verifyDirectory(
            Path root,
            Path directory,
            Map<String, List<FileHash>> expected,
            Set<String> actualPaths,
            Set<Path> activeDirectories,
            List<String> errors) throws IOException {

        Path realDirectory = directory.toRealPath();

        if (!activeDirectories.add(realDirectory)) {
            errors.add(
                    "Symbolic-link directory cycle detected: "
                            + directory);
            return;
        }

        try {
            String relativeDirectory = toPortableRelativeDirectory(root, directory);

            Map<String, String> expectedFiles = new HashMap<>();

            List<FileHash> expectedEntries = expected.get(relativeDirectory);

            if (expectedEntries != null) {
                for (FileHash file : expectedEntries) {
                    if (expectedFiles.put(
                            file.fileName,
                            bytesToHex(file.hash)) != null) {
                        errors.add(
                                "Duplicate file entry in hash file: "
                                        + combineRelativePath(
                                                relativeDirectory,
                                                file.fileName));
                    }
                }
            }

            try (var stream = Files.list(directory)) {
                var iterator = stream.iterator();

                while (iterator.hasNext()) {
                    Path path = iterator.next();

                    if (isHashFile(root, path)) {
                        continue;
                    }

                    if (Files.isRegularFile(path)) {
                        String fileName = path.getFileName().toString();
                        String relativePath = combineRelativePath(
                                relativeDirectory,
                                fileName);

                        actualPaths.add(relativePath);

                        String expectedHash = expectedFiles.get(fileName);

                        if (expectedHash == null) {
                            errors.add(
                                    "Unexpected file: " + relativePath);
                            continue;
                        }

                        byte[] actualHash;

                        try {
                            actualHash = calculateFileHash(path);
                        } catch (IOException e) {
                            errors.add(
                                    "Failed to hash file: "
                                            + relativePath
                                            + " ("
                                            + e.getMessage()
                                            + ")");
                            continue;
                        }

                        String actualHashHex = bytesToHex(actualHash);

                        if (!actualHashHex.equals(expectedHash)) {
                            errors.add(
                                    "Hash mismatch: "
                                            + relativePath);
                        }
                    } else if (Files.isDirectory(path)) {
                        verifyDirectory(
                                root,
                                path,
                                expected,
                                actualPaths,
                                activeDirectories,
                                errors);
                    }
                }
            }
        } finally {
            activeDirectories.remove(realDirectory);
        }
    }

    // -------------------------------------------------------------------------
    // ハッシュ計算
    // -------------------------------------------------------------------------

    private static byte[] calculateFileHash(Path file)
            throws IOException {

        MessageDigest digest = newSha256();

        try (InputStream input = new BufferedInputStream(
                Files.newInputStream(file),
                BUFFER_SIZE)) {

            byte[] buffer = new byte[BUFFER_SIZE];

            while (true) {
                int read = input.read(buffer);

                if (read == -1) {
                    break;
                }

                digest.update(buffer, 0, read);
            }
        }

        return digest.digest();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            /*
             * SHA-256 は Java の標準暗号実装で必須なので、
             * 通常この例外は発生しない。
             */
            throw new AssertionError(
                    "SHA-256 is not available",
                    e);
        }
    }

    // -------------------------------------------------------------------------
    // .hash.toml 書き込み
    // -------------------------------------------------------------------------

    private static void writeHashFile(
            Path file,
            Map<String, List<FileHash>> entries) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8)) {

            writer.write(HEADER);
            writer.newLine();

            /*
             * root の table は []。
             */
            boolean rootWritten = false;

            List<FileHash> rootEntries = entries.get("");

            if (rootEntries != null && !rootEntries.isEmpty()) {
                writer.write("[]");
                writer.newLine();

                writeFileEntries(writer, rootEntries);

                rootWritten = true;
            }

            /*
             * LinkedHashMap互換の順序をそのまま利用する。
             */
            for (Map.Entry<String, List<FileHash>> entry : entries.entrySet()) {

                String directory = entry.getKey();

                if (directory.isEmpty()) {
                    continue;
                }

                if (rootWritten || !directory.isEmpty()) {
                    writer.newLine();
                }

                writer.write("[\"");
                writer.write(escapeTomlBasicString(directory));
                writer.write("\"]");
                writer.newLine();

                writeFileEntries(writer, entry.getValue());

                rootWritten = true;
            }

            /*
             * ファイルが1個も存在しない場合でも、
             * hash file として有効な root table を作る。
             */
            if (!rootWritten) {
                writer.write("[]");
                writer.newLine();
            }
        }
    }

    private static void writeFileEntries(
            BufferedWriter writer,
            List<FileHash> entries) throws IOException {

        for (FileHash entry : entries) {
            writer.write("\"");
            writer.write(
                    escapeTomlBasicString(entry.fileName));
            writer.write("\" = \"");
            writer.write(bytesToHex(entry.hash));
            writer.write("\"");
            writer.newLine();
        }
    }

    // -------------------------------------------------------------------------
    // .hash.toml 読み込み
    // -------------------------------------------------------------------------

    private static Map<String, List<FileHash>> readHashFile(
            Path file) throws IOException {

        Map<String, List<FileHash>> result = new LinkedHashMapCompat<>();

        String currentDirectory = null;

        List<String> lines = Files.readAllLines(
                file,
                StandardCharsets.UTF_8);

        int lineNumber = 0;

        for (String line : lines) {
            lineNumber++;

            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("#")) {
                continue;
            }

            if (trimmed.startsWith("[") &&
                    trimmed.endsWith("]")) {

                currentDirectory = parseTableHeader(
                        trimmed,
                        lineNumber);

                if (result.containsKey(currentDirectory)) {
                    throw new IOException(
                            "Duplicate table at line "
                                    + lineNumber
                                    + ": "
                                    + currentDirectory);
                }

                result.put(
                        currentDirectory,
                        new ArrayList<>());

                continue;
            }

            if (currentDirectory == null) {
                throw new IOException(
                        "Key appears before a table at line "
                                + lineNumber);
            }

            ParsedAssignment assignment = parseAssignment(
                    trimmed,
                    lineNumber);

            result.get(currentDirectory).add(
                    new FileHash(
                            assignment.key,
                            hexToBytes(assignment.value)));
        }

        return result;
    }

    private static String parseTableHeader(
            String line,
            int lineNumber) throws IOException {

        if (line.equals("[]")) {
            return "";
        }

        if (!line.startsWith("[\"") ||
                !line.endsWith("\"]")) {

            throw new IOException(
                    "Invalid table header at line "
                            + lineNumber
                            + ": "
                            + line);
        }

        String escaped = line.substring(
                2,
                line.length() - 2);

        return parseTomlBasicString(
                escaped,
                lineNumber);
    }

    private static ParsedAssignment parseAssignment(
            String line,
            int lineNumber) throws IOException {

        int equals = findAssignmentEquals(line);

        if (equals < 0) {
            throw new IOException(
                    "Invalid assignment at line "
                            + lineNumber
                            + ": "
                            + line);
        }

        String keyPart = line.substring(0, equals).trim();

        String valuePart = line.substring(equals + 1).trim();

        if (!isQuoted(keyPart) ||
                !isQuoted(valuePart)) {

            throw new IOException(
                    "Only quoted string keys and values are "
                            + "supported at line "
                            + lineNumber);
        }

        String key = parseTomlBasicString(
                keyPart.substring(
                        1,
                        keyPart.length() - 1),
                lineNumber);

        String value = parseTomlBasicString(
                valuePart.substring(
                        1,
                        valuePart.length() - 1),
                lineNumber);

        return new ParsedAssignment(key, value);
    }

    private static int findAssignmentEquals(String line) {
        boolean escaped = false;
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\' && quoted) {
                escaped = true;
                continue;
            }

            if (c == '"') {
                quoted = !quoted;
                continue;
            }

            if (c == '=' && !quoted) {
                return i;
            }
        }

        return -1;
    }

    private static boolean isQuoted(String value) {
        return value.length() >= 2 &&
                value.charAt(0) == '"' &&
                value.charAt(value.length() - 1) == '"';
    }

    private static String parseTomlBasicString(
            String value,
            int lineNumber) throws IOException {

        StringBuilder result = new StringBuilder();

        boolean escaped = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (!escaped) {
                if (c == '\\') {
                    escaped = true;
                } else {
                    result.append(c);
                }

                continue;
            }

            escaped = false;

            switch (c) {
                case 'b':
                    result.append('\b');
                    break;

                case 't':
                    result.append('\t');
                    break;

                case 'n':
                    result.append('\n');
                    break;

                case 'f':
                    result.append('\f');
                    break;

                case 'r':
                    result.append('\r');
                    break;

                case '"':
                    result.append('"');
                    break;

                case '\\':
                    result.append('\\');
                    break;

                default:
                    throw new IOException(
                            "Unsupported TOML escape sequence "
                                    + "\\"
                                    + c
                                    + " at line "
                                    + lineNumber);
            }
        }

        if (escaped) {
            throw new IOException(
                    "Unterminated escape sequence at line "
                            + lineNumber);
        }

        return result.toString();
    }

    // -------------------------------------------------------------------------
    // TOML文字列
    // -------------------------------------------------------------------------

    private static String escapeTomlBasicString(
            String value) {

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            switch (c) {
                case '\b':
                    result.append("\\b");
                    break;

                case '\t':
                    result.append("\\t");
                    break;

                case '\n':
                    result.append("\\n");
                    break;

                case '\f':
                    result.append("\\f");
                    break;

                case '\r':
                    result.append("\\r");
                    break;

                case '"':
                    result.append("\\\"");
                    break;

                case '\\':
                    result.append("\\\\");
                    break;

                default:
                    /*
                     * TOMLのbasic stringにそのまま書けるUnicode文字は
                     * UTF-8で出力する。
                     */
                    result.append(c);
                    break;
            }
        }

        return result.toString();
    }

    // -------------------------------------------------------------------------
    // パス
    // -------------------------------------------------------------------------

    private static Path normalizeRoot(Path root)
            throws IOException {

        if (root == null) {
            throw new IllegalArgumentException(
                    "root must not be null");
        }

        root = root.toAbsolutePath().normalize();

        if (!Files.isDirectory(root)) {
            throw new IOException(
                    "Not a directory: " + root);
        }

        return root;
    }

    private static boolean isHashFile(
            Path root,
            Path path) {
        return path.equals(root.resolve(HASH_FILE_NAME));
    }

    /**
     * OS依存の separator を使わず、
     * rootからの相対ディレクトリを / 区切りに変換する。
     */
    private static String toPortableRelativeDirectory(
            Path root,
            Path directory) {

        Path relative = root.relativize(directory);

        if (relative.getNameCount() == 0) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                result.append('/');
            }

            result.append(
                    relative.getName(i).toString());
        }

        return result.toString();
    }

    private static String combineRelativePath(
            String directory,
            String fileName) {

        if (directory.isEmpty()) {
            return fileName;
        }

        return directory + "/" + fileName;
    }

    // -------------------------------------------------------------------------
    // Hex
    // -------------------------------------------------------------------------

    private static String bytesToHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();

        char[] result = new char[bytes.length * 2];

        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;

            result[i * 2] = digits[value >>> 4];

            result[i * 2 + 1] = digits[value & 0x0f];
        }

        return new String(result);
    }

    private static byte[] hexToBytes(
            String hex) throws IOException {

        if (hex.length() != 64) {
            throw new IOException(
                    "SHA-256 hash must contain exactly 64 "
                            + "hexadecimal characters: "
                            + hex);
        }

        byte[] result = new byte[32];

        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(
                    hex.charAt(i * 2),
                    16);

            int low = Character.digit(
                    hex.charAt(i * 2 + 1),
                    16);

            if (high < 0 || low < 0) {
                throw new IOException(
                        "Invalid hexadecimal SHA-256 hash: "
                                + hex);
            }

            result[i] = (byte) ((high << 4) | low);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // データクラス
    // -------------------------------------------------------------------------

    private static final class FileHash {
        private final String fileName;
        private final byte[] hash;

        private FileHash(
                String fileName,
                byte[] hash) {
            this.fileName = fileName;
            this.hash = hash;
        }
    }

    private static final class ParsedAssignment {
        private final String key;
        private final String value;

        private ParsedAssignment(
                String key,
                String value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * Java標準ライブラリのLinkedHashMapをそのまま使うための
     * 名前付き薄いラッパー。
     *
     * ファイルシステムから得たディレクトリの列挙順を保持する。
     */
    private static final class LinkedHashMapCompat<K, V>
            extends java.util.LinkedHashMap<K, V> {
    }
}