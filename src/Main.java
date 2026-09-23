package src;

import java.nio.file.Path;

public class Main {
    private static class Tasks {
        public boolean willLoad;
        public boolean willRehash;
        public boolean willCheckHash;
        public boolean willRefresh;
    }

    public static void main(String[] args) {
        // 引数を解釈
        final String targetDir = getTargetDirectoryFromArgs(args, "D:\\");
        final Tasks tasks = getTasksFromArgs(args);

        // 対象のディレクトリを指定
        final Path targetRoot = targetDir != null ? Path.of(targetDir) : Path.of("D:\\");

        // タスクに応じて処理を実行:順は大事
        try {
            if (tasks.willCheckHash) {
                System.out.println("------------------------------------------------------------------------------");
                System.out.println("[INFO] Starting hash verification...");
                DirectoryHasher.verifyHash(targetRoot);
                System.out.println("[INFO] Hash verification completed.");
                System.out.println("------------------------------------------------------------------------------");
            }
            if (tasks.willLoad) {
                System.out.println("------------------------------------------------------------------------------");
                System.out.println("[INFO] Starting memory loading...");
                DirectoryLoader.load(targetRoot);
                System.out.println("[INFO] Memory loading completed.");
                System.out.println("------------------------------------------------------------------------------");
            }
            if (tasks.willRefresh) {
                System.out.println("------------------------------------------------------------------------------");
                System.out.println("[INFO] Starting directory refresh...");
                DirectoryRefresher.refresh(targetRoot);
                System.out.println("[INFO] Directory refresh completed.");
                System.out.println("------------------------------------------------------------------------------");
            }
            if (tasks.willRehash) {
                System.out.println("------------------------------------------------------------------------------");
                System.out.println("[INFO] Starting hash re-calculation...");
                DirectoryHasher.createHash(targetRoot);
                System.out.println("[INFO] Hash re-calculation completed.");
                System.out.println("------------------------------------------------------------------------------");
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Exception occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 引数からターゲットディレクトリを取得する。--target オプションが指定されていない場合はデフォルトディレクトリを返す。
     */
    private static String getTargetDirectoryFromArgs(String[] args, final String defaultDir) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-target") && i + 1 < args.length) {
                final String targetDir = args[i + 1];
                System.out.println("[INFO] Refresh Target directory: " + targetDir);
                return targetDir;
            }
        }
        System.out.println("[INFO] No target directory specified. Defaulting to " + defaultDir);
        return null;
    }

    /**
     * 引数からタスクを特定する。
     * --load : メモリロード
     * --refresh : ディレクトリリフレッシュ
     * --check-hash : ハッシュチェック
     * --rehash : ハッシュ再計算
     */
    private static Tasks getTasksFromArgs(String[] args) {
        Tasks tasks = new Tasks();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--load":
                    tasks.willLoad = true;
                    break;
                case "--refresh":
                    tasks.willRefresh = true;
                    break;
                case "--check-hash":
                    tasks.willCheckHash = true;
                    break;
                case "--rehash":
                    tasks.willRehash = true;
                    break;
            }
        }
        return tasks;
    }
}