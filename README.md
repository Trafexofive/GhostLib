# GhostLib - Drone Construction Engine

## Overview
GhostLib is a library and core engine for Minecraft 1.21.1 (NeoForge) that implements a high-performance, automated construction system using drones. The core philosophy is "physical interaction" — blocks are never instantly created or destroyed; they are built or deconstructed by flying entities.

## Core Architecture

### 1. GhostJobManager (`com.example.ghostlib.util.GhostJobManager`)
The "Brain" of the operation. This singleton (per level) manages all active construction and deconstruction tasks.
*   **Spatial Partitioning:** Jobs are indexed by Chunk Coordinate (`long key`) for O(1) retrieval.
*   **Queues:**
    *   `constructionJobs`: Valid placement jobs waiting for a drone.
    *   `ghostRemovalJobs`: Jobs to remove ghost markers.
    *   `directDeconstructJobs`: Jobs to remove physical blocks (solid obstructions).
    *   `hibernatingJobs`: Jobs that failed item retrieval (Missing Items). They wake up every 5 seconds to retry.
*   **Atomic Assignment:** Uses an `assignedPositions` map to lock specific block positions to specific drone UUIDs, preventing race conditions.
*   **Network Optimization:** Uses a `dirty` flag to only sync deconstruction overlays to clients when changes occur, rather than every tick.

### 2. DroneEntity (`com.example.ghostlib.entity.DroneEntity`)
The worker unit. A `PathfinderMob` with custom AI logic (no standard goals).
*   **Finite State Machine (FSM):**
    *   `IDLE`: Hovering, looking for work.
    *   `FINDING_JOB`: Querying the `GhostJobManager` based on spatial proximity (rings search).
    *   `TRAVELING_FETCH`: Moving to the player to take items from inventory.
    *   `TRAVELING_BUILD`: Moving to the ghost to place the block.
    *   `TRAVELING_CLEAR`: Moving to a site to break a block (harvest).
    *   `DUMPING_ITEMS`: Returning harvested items to the player.
*   **Logic:**
    *   **Data-Race Prevention:** Re-checks item availability immediately before taking from player inventory.
    *   **Obstruction Handling:** If a block (e.g., Gravel) falls into the build site *after* the job started, the drone detects it upon arrival, cancels the build, and registers a `directDeconstruct` job instead.

### 3. GhostBlockEntity (`com.example.ghostlib.block.entity.GhostBlockEntity`)
The "State Holder". Every blue ghost block is a TileEntity that tracks its own lifecycle.
*   **States:**
    1.  `UNASSIGNED` (Deep Blue): Waiting for a drone.
    2.  `ASSIGNED` (Light Blue): Drone is en route.
    3.  `FETCHING` (Dark Blue): Drone is getting items.
    4.  `INCOMING` (Yellow): Drone has item, delivering.
    5.  `TO_REMOVE` (Red Wireframe): Marked for deletion.
    6.  `REMOVING` (Red Wireframe): Drone is currently breaking it.
    7.  `MISSING_ITEMS`: Drone couldn't find items (hibernating).
*   **Persistence:** Uses `onLoad()` to re-register itself with the `GhostJobManager` on server start/chunk load, ensuring no jobs are lost.

### 4. GhostHistoryManager (`com.example.ghostlib.history.GhostHistoryManager`)
Manages Undo/Redo functionality with atomic transactions.
*   **Atomic Batches:** Records placements as a `List<GhostRecord>` so an entire wall is undone in one click.
*   **Drone-Driven Undo:**
    *   Undoing a **Ghost** -> Instant removal.
    *   Undoing a **Solid Block** -> Registers a `DirectDeconstruct` job. Drones must fly out and remove the blocks physically.

## Visuals (`GhostBlockRenderer`)
*   **Standard Ghosts:** Rendered as translucent cubes with colors corresponding to their state (Blue/Yellow).
*   **Deconstruction:** Rendered as a "Red Wireframe + Translucent Red Tint" overlay on top of the existing solid block. This persists during the `TO_REMOVE` and `REMOVING` states.

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
