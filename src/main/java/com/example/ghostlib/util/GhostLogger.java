package com.example.ghostlib.util;

import net.neoforged.fml.loading.FMLPaths;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class GhostLogger {
    private static File logFile;
    private static BufferedWriter writer;
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    public static void init() {
        try {
            File dir = FMLPaths.GAMEDIR.get().resolve("logs/ghostlib").toFile();
            if (!dir.exists()) dir.mkdirs();
            // Rotate logs or just use a timestamped one
            logFile = new File(dir, "verbose.log");
            // Append mode false to clear on restart
            writer = new BufferedWriter(new FileWriter(logFile, false));
            log("SYSTEM", "GhostLib Verbose Logger Initialized");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void log(String category, String message) {
        if (writer == null) return;
        try {
            writer.write(String.format("[%s] [%s] %s", dtf.format(LocalDateTime.now()), category, message));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void drone(String message) { log("DRONE", message); }
    public static void multiblock(String message) { log("MULTIBLOCK", message); }
    public static void energy(String message) { log("ENERGY", message); }
    public static void logistics(String message) { log("LOGISTICS", message); }
    public static void network(String message) { log("NETWORK", message); }
    public static void performance(String message) { log("PERFORMANCE", message); }
}
