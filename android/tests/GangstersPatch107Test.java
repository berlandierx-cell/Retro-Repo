package com.elmagnifico.retroiso.runtime.winlator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GangstersPatch107Test {
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("retroiso-patch-test-");
        try {
            Map<String, String> manifest = new LinkedHashMap<>();
            for (String name : new String[]{"Game.exe", "language/patch.lan", "Update.txt"}) {
                Path file = root.resolve(name);
                Files.createDirectories(file.getParent());
                Files.writeString(file, "fixture: " + name);
                manifest.put(name, GangstersPatch107.sha256(file.toFile()));
            }
            check(GangstersPatch107.verifyFiles(root.toFile(), manifest), "Complete update");
            Files.move(root.resolve("language"), root.resolve("Language"));
            check(GangstersPatch107.verifyFiles(root.toFile(), manifest), "Wine path casing");
            Files.writeString(root.resolve("Game.exe"), "partial update");
            check(!GangstersPatch107.verifyFiles(root.toFile(), manifest), "Changed EXE must fail");
            Files.writeString(root.resolve("Game.exe"), "fixture: Game.exe");
            Files.delete(root.resolve("Update.txt"));
            Files.writeString(root.resolve("patch107.applied"), "old optimistic marker");
            check(!GangstersPatch107.verifyFiles(root.toFile(), manifest), "Marker cannot validate missing file");
            check(!GangstersPatch107.isApplied(root.toFile()), "Unrelated files cannot validate known patch");
            check(!GangstersPatch107.verifyFiles(root.toFile(), Map.of("../outside", "anything")), "No traversal");
            Files.writeString(root.resolve("game.exe"), "fixture: Game.exe");
            check(!GangstersPatch107.verifyFiles(root.toFile(), Map.of("Game.exe", manifest.get("Game.exe"))), "Ambiguous case");
            // Optional local check against independently extracted user-supplied payload.
            if (args.length > 0) check(GangstersPatch107.isApplied(new File(args[0])), "Actual 1.0.7 payload hashes");
            System.out.println("Patch verification regression tests passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
