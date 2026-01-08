# GhostLib - Drone Construction Engine

## Overview
GhostLib is a library and core engine for Minecraft 1.21.1 (NeoForge) that implements a high-performance, automated construction system using drones. The core philosophy is "physical interaction" — blocks are never instantly created or destroyed; they are built or deconstructed by flying entities.

## Core Architecture

### 1. GhostJobManager (`com.example.ghostlib.util.GhostJobManager`)
The "Brain" of the operation. This singleton (per level) manages all active construction and deconstruction tasks.
*   **Spatial Partitioning:** Jobs are indexed by Chunk Coordinate (`long key`) for O(1) retrieval.
*   **Persistent Logic:** Jobs and states are saved via `SavedData` to survive world restarts.
*   **Queues:** Manages `constructionJobs`, `ghostRemovalJobs`, `directDeconstructJobs`, and `hibernatingJobs` (for missing items).

### 2. Drone Swarm (`com.example.ghostlib.entity`)
Drones are custom AI entities that handle the physical labor.
*   **Player-Owned Drones:** Follow the player and fetch items from their inventory.
*   **Port-Owned Drones (`PortDroneEntity`):** Reside in a **Drone Port**. They fetch items from nearby containers and return home when idle or low on power.
*   **Finite State Machine (FSM):** Drones cycle through states like `FINDING_JOB`, `TRAVELING_FETCH`, `TRAVELING_BUILD`, and `DUMPING_ITEMS`.

### 3. Drone Port & Multiblocks
The **Drone Port** is a 3x3 multiblock structure that serves as a hangar and charging station for drones.
*   **Autonomous Deployment:** Automatically spawns drones when jobs are detected within range.
*   **Energy Integration:** Consumes VoltLink Flux power to deploy and maintain drones.
*   **Inventory Storage:** Holds a stack of Drone Eggs and provides a workspace for swarm coordination.

### 4. Blueprint System
Blueprints allow for the projection of complex structures into ghost blocks.
*   **Electric Furnace Blueprint:** A starter item that projects a 3x3 base structure.
*   **Drag-to-Place:** Uses the same high-fidelity preview system as the standard Placer.
*   **Auto-Patterning:** Items can be configured to automatically generate patterns when held.

## VoltLink Integration
GhostLib is designed to work seamlessly with **VoltLink** for power management.
*   **Wireless Charging:** Drone Ports can draw power wirelessly from **Electric Floors(should be electric wires)** connected to the grid.
*   **Grid Demand:** Ports request power from the grid only when needed (e.g., during deployment).

## Configuration (`config/*.yml`)
GhostLib uses YAML-based configuration for easy tuning:
*   `drone.yml`: Health, speed, and interaction ranges.
*   `drone_port.yml`: Energy capacity, spawn costs, and max active drones.

## Visuals (`GhostBlockRenderer`)
*   **Standard Ghosts:** Rendered as translucent cubes with colors corresponding to their state (Blue/Yellow).
*   **Deconstruction:** Rendered as a "Red Wireframe + Translucent Red Tint" overlay.

## Key Logic Flows

### Construction Lifecycle
1.  Player places `GhostBlock`. State = `UNASSIGNED`.
2.  `GhostJobManager` registers job.
3.  `Drone` scans local chunks, finds job.
4.  `Drone` claims job. `GhostBlock` State = `ASSIGNED`.
5.  `Drone` checks inventory.
    *   If missing: Fly to Player -> `GhostBlock` State = `FETCHING`.
    *   If present: Fly to Ghost -> `GhostBlock` State = `INCOMING`.
6.  `Drone` arrives at Ghost.
    *   Obstruction Check: Is there gravel/sand/player block?
        *   Yes: Register `DirectDeconstruct` -> Reset Drone.
        *   No: Place Block -> Remove Ghost -> `IDLE`.

### Deconstruction Lifecycle
1.  System (Undo or Placement Overlap) requests removal of `(10, 64, 10)`.
2.  `GhostJobManager` registers `DirectDeconstruct` job. Syncs Red Overlay to Client.
3.  `Drone` claims job.
4.  `Drone` flies to `(10, 64, 10)`.
5.  `Drone` breaks block, adds to internal inventory.
6.  `Drone` returns item to player.

## Edge Cases Handled
*   **Gravel/Sand:** Falling blocks detected at build time trigger auto-deconstruction.
*   **Replaceable Blocks:** Tall Grass, Snow, Water are treated as Air by the `GhostPlacerItem`.
*   **Network Spam:** Packets only sent on job list changes (`dirty` flag).
*   **Server Restart:** Ghosts re-add themselves to the queue on load.
