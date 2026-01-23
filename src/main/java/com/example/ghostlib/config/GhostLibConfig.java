package com.example.ghostlib.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class GhostLibConfig {
    // Port Config
    public static int PORT_ENERGY_CAPACITY = 1000000;
    public static int PORT_ENERGY_PER_SPAWN = 1000;
    public static int PORT_MAX_ACTIVE_DRONES = 32;
    public static int PORT_ACTIVATION_RANGE = 64;

    // Drone Config
    public static double DRONE_MAX_HEALTH = 20.0;
    public static double DRONE_MOVEMENT_SPEED = 1.0;
    public static double DRONE_INTERACTION_RANGE = 12.0;
    public static int DRONE_SEARCH_RANGE_H = 32;
    public static int DRONE_SEARCH_RANGE_V = 16;
    public static boolean RENDER_DRONE_BEAMS = true;
    
    // UX Config
    public static boolean EXIT_MODE_AFTER_PLACE = true;

    public static void load() {
        loadYaml("drone_port.yml", "port");
        loadYaml("drone.yml", "drone");
    }

    private static void loadYaml(String fileName, String type) {
        Path configPath = Paths.get("config", fileName);
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath.getParent());
                String content = "";
                if (type.equals("drone")) {
                    content = """
                    attributes:
                      max_health: 20.0
                      movement_speed: 1.0
                    logic:
                      interaction_range: 12.0
                      search_range_horizontal: 32
                      search_range_vertical: 16
                    visuals:
                      render_beams: true # Aesthetic laser beams on place/break
                    ux:
                      exit_mode_after_place: true # Automatically exit selection mode after confirming action
                    """;
                } else {
                    content = """
                    port:
                      energy_capacity: 1000000
                      energy_per_spawn: 1000
                      max_active_drones: 16
                      activation_range: 64
                    """;
                }
                Files.writeString(configPath, content);
            } catch (IOException e) { e.printStackTrace(); }
        }

        try {
            List<String> lines = Files.readAllLines(configPath);
            String section = "";
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.endsWith(":")) {
                    section = line.substring(0, line.length() - 1) + ".";
                    continue;
                }
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String key = section + parts[0].trim();
                    String val = parts[1].split("#")[0].trim();
                    applyValue(key, val);
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static void applyValue(String key, String value) {
        try {
            switch (key) {
                case "port.energy_capacity" -> PORT_ENERGY_CAPACITY = Integer.parseInt(value);
                case "port.energy_per_spawn" -> PORT_ENERGY_PER_SPAWN = Integer.parseInt(value);
                case "port.max_active_drones" -> PORT_MAX_ACTIVE_DRONES = Integer.parseInt(value);
                case "port.activation_range" -> PORT_ACTIVATION_RANGE = Integer.parseInt(value);
                
                case "attributes.max_health" -> DRONE_MAX_HEALTH = Double.parseDouble(value);
                case "attributes.movement_speed" -> DRONE_MOVEMENT_SPEED = Double.parseDouble(value);
                case "logic.interaction_range" -> DRONE_INTERACTION_RANGE = Double.parseDouble(value);
                case "logic.search_range_horizontal" -> DRONE_SEARCH_RANGE_H = Integer.parseInt(value);
                case "logic.search_range_vertical" -> DRONE_SEARCH_RANGE_V = Integer.parseInt(value);
                case "visuals.render_beams" -> RENDER_DRONE_BEAMS = Boolean.parseBoolean(value);
                case "ux.exit_mode_after_place" -> EXIT_MODE_AFTER_PLACE = Boolean.parseBoolean(value);
            }
        } catch (Exception e) {}
    }
}
