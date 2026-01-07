# Technical Architecture: GhostLib

## 1. Drone AI Lifecycle
Drones do not use the standard Minecraft `GoalSelector` system. Instead, they use a custom Finite State Machine (FSM) implemented in `DroneEntity#tick()`. This allows for high-precision movement and atomic task execution.

### Smooth Movement Logic
Drones use `moveSmoothlyTo(Vec3 target, double speed)`. 
*   **Approach Damping:** As a drone approaches within 2.0 blocks of its target, its speed is linearly scaled down based on distance. This prevents "jittering" or orbiting the target and allows for exact landings at the build site.
*   **Linger Period:** Upon completing a task, drones enter a 0.5s (10 tick) `lingerTicks` phase where they decelerate to zero and wait. This provides visual weight and prevents the drones from looking too "robotic" or jittery during high-speed transitions.

## 2. The Networking Protocol
GhostLib minimizes network traffic by strictly separating "World State" from "Renderer State".

### Ghost Syncing
Standard ghost colors (Blue/Yellow) are synced via standard BlockEntity `getUpdatePacket`. This only happens when the ghost's internal state changes.

### Deconstruction Overlays
Because deconstruction jobs are "Global" tasks that don't always have a BlockEntity (e.g., when a drone is clearing a solid Stone block), we use a custom packet: `S2CSyncDeconstructionPacket`.
*   **Trigger:** The `GhostJobManager` tracks a `dirty` flag. It only broadcasts the global list of active deconstruction sites when a job is added or removed.
*   **Render:** The client caches this list and renders the Red Wireframe overlay for every position in the list.

## 3. Atomic Consistency (Undo/Redo)
The `GhostHistoryManager` ensures that world changes are always reversible and stable.

*   **Snapshotting:** When a player drags a blueprint, the world state *before* any changes is captured in a `GhostRecord`.
*   **Physical Restoration:** Undoing a build doesn't just delete the blocks. It generates a `DIRECT_DECONSTRUCT` job. A drone must physically arrive, break the block, and return the item to the player.
*   **Replaceable Logic:** Blocks like Grass or Snow are treated as AIR in the history snapshots to prevent "Vegetation Ghosts" that require the player to replant flowers to clear an undo.

## 4. Spatial Partitioning
To handle hundreds of jobs across thousands of blocks, `GhostJobManager` partitions tasks by Chunk (`ChunkPos.asLong`).
*   **Lookup:** `requestJob` only searches a 7x7 chunk area around the drone.
*   **Complexity:** Searching for a job is $O(R^2)$ where R is the chunk radius, rather than $O(N)$ where N is the total number of jobs. This ensures the system remains fast even with massive factory builds.
