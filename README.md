# GhostLib - Drone Construction Engine

## Overview
GhostLib is a library and core engine for Minecraft 1.21.1 (NeoForge) that implements a Factorio-grade, high-performance, automated construction system using drones. The core philosophy is "physical interaction" — blocks are never instantly created or destroyed; they are built or deconstructed by flying entities. Designed for scalability with support for 10,000+ concurrent operations.

## Core Architecture

### 1. GhostJobManager (`com.example.ghostlib.util.GhostJobManager`)
The "Brain" of the operation. This singleton (per level) manages all active construction and deconstruction tasks.
*   **Spatial Partitioning:** Jobs are indexed by Chunk Coordinate (`long key`) for O(1) retrieval.
*   **Persistent Logic:** Jobs and states are saved via `SavedData` to survive world restarts.
*   **Queues:** Manages `constructionJobs`, `ghostRemovalJobs`, `directDeconstructJobs`, and `hibernatingJobs` (for missing items).

### 2. Drone Swarm (`com.example.ghostlib.entity`)
Drones are custom AI entities that handle the physical labor.
*   **Attributes:** Drones use a high-fidelity attribute system for `work_speed`, `interaction_range`, `search_range`, and `energy_efficiency`. These can be modified via upgrades or tier progression.
*   **Player-Owned Drones:** Follow the player and fetch items from their inventory.
*   **Port-Owned Drones:** Reside in a **Drone Port**. They fetch items from nearby containers and return home when idle or low on power.
*   **Finite State Machine (FSM):** Drones cycle through states like `FINDING_JOB`, `TRAVELING_FETCH`, `TRAVELING_BUILD`, and `DUMPING_ITEMS`.

### 3. Logistics & Multiblocks
*   **Drone Port:** A 3x3x3 multiblock hangar. Drones dock at the top-center controller to charge and swap items. Pulls energy from the floor below the structure.
*   **Logistical Chests:** Special containers (Provider, Requester, Storage, Buffer) that allow drones to autonomously manage inventory.
*   **Smart Harvest:** When a drone breaks a container (Chest, etc.), it captures the contents into NBT and clears the block's inventory BEFORE removal. This prevents "item spewing" and enables high-fidelity transport of machines.

### 4. Blueprint System
Blueprints allow for the projection of complex structures into ghost blocks.
*   **Global Selection:** Use `Ctrl+C`, `Ctrl+V`, `Ctrl+X` to manage patterns globally.
*   **NBT Preservation:** Blueprints capture block entity data (inventory, settings). In Creative mode, these are restored instantly. In Survival, drones restore data if the construction item matches the original.
*   **Super Force Build:** When placing a blueprint over an obstruction, the system displays a cyan ghost overlay inside the red deconstruction wireframe, confirming the final intent.

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
