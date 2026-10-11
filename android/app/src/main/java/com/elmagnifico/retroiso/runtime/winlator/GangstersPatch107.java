package com.elmagnifico.retroiso.runtime.winlator;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Metadata verified from the user-supplied Hothouse 1.0.7 updater.
 * The length-prefixed REGKEY is 19 bytes: Software\\Gangsters2.
 * The adjacent 0x67 belongs to the following DWORD, not to the key name.
 * No game binaries are bundled here.
 */
public final class GangstersPatch107 {
    public static final String REGISTRY_KEY = "Software\\Gangsters2";
    public static final String REGISTRY_KEY_32 = "Software\\Wow6432Node\\Gangsters2";
    private static final Map<String, String> FILES = new LinkedHashMap<>();
    static {
        FILES.put("Gangsters2.exe", "710573fa7c4fa838e2d6814ae3b83358bfd28529d679a713915d42d2d01707ac");
        FILES.put("Gangsters2SpawnedVersion.exe", "be329433ec752a8c10bfe2e25d02f815e5858ec2676f58eba2966add5bf30105");
        FILES.put("language/patch1.lan", "700b9659d3e4555e2e01d089b37e56564066f1023eee1ff268579d467911d5c7");
        FILES.put("Update.txt", "111c210f56862d09fcd4ff77559cc65ce4b18a89c08a6c06c07df1bc5471c499");
    }

    private GangstersPatch107() {}

    public static boolean isApplied(File gameDirectory) {
        return verifyFiles(gameDirectory, FILES);
    }

    // Package-visible for filesystem regression tests, without shipping game data.
    static boolean verifyFiles(File gameDirectory, Map<String, String> expected) {
        try {
            File root = gameDirectory.getCanonicalFile();
            if (!root.isDirectory() || expected.isEmpty()) return false;
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                File file = root;
                for (String component : entry.getKey().split("/")) {
                    if (component.isEmpty() || component.equals(".") || component.equals("..")) return false;
                    File[] candidates = file.listFiles((dir, name) -> name.equalsIgnoreCase(component));
                    if (candidates == null || candidates.length != 1) return false;
                    file = candidates[0].getCanonicalFile();
                    if (!file.getPath().startsWith(root.getPath() + File.separator)) return false;
                }
                if (!file.isFile() || !entry.getValue().equals(sha256(file))) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = java.nio.file.Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
