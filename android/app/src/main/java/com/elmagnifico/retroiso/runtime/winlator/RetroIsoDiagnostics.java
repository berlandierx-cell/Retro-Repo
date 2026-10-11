package com.elmagnifico.retroiso.runtime.winlator;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded, local-only launch logs; no network or automatic sharing. */
public final class RetroIsoDiagnostics {
    private static final int LIMIT = 120000;
    private final File file;
    private final String header;
    private final StringBuilder lines = new StringBuilder();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private boolean dirty = true;
    private boolean closed = false;

    public RetroIsoDiagnostics(Context context, String launchInfo) {
        File dir = new File(context.getFilesDir(), "launch-diagnostics");
        dir.mkdirs();
        File[] old = dir.listFiles((d, name) -> name.endsWith(".txt"));
        if (old != null) {
            Arrays.sort(old, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = 2; i < old.length; i++) old[i].delete();
        }
        file = new File(dir, System.currentTimeMillis() + "-" + System.nanoTime() + ".txt");
        header = "Retro ISO 0.5.3\nDate: " + new java.util.Date() +
            "\nAndroid: " + android.os.Build.VERSION.SDK_INT +
            "\nAppareil: " + android.os.Build.MODEL + "\n" + launchInfo + "\n\n";
        flush();
        writer.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
    }

    public synchronized void append(String line) {
        if (closed) return;
        lines.append(line).append('\n');
        if (lines.length() > LIMIT) lines.delete(0, lines.length() - LIMIT);
        dirty = true;
    }

    public synchronized void flush() {
        if (!dirty) return;
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write((header + lines).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return; }
        if (temp.renameTo(file)) dirty = false;
    }

    public synchronized void close() {
        if (closed) return;
        flush();
        closed = true;
        writer.shutdownNow();
    }

    public static String readLatest(Context context) {
        File dir = new File(context.getFilesDir(), "launch-diagnostics");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0)
            return "Aucun diagnostic enregistré. Lance un programme avec cette version, puis reviens ici.";
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        try {
            return new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Impossible de lire le diagnostic : " + e.getMessage();
        }
    }
}

