# Technical Architecture: GhostLib

## 1. World Management: The Command Ledger
GhostLib has transitioned from a simple job-queue model to a **Command Ledger Architecture**. Every world interaction is recorded in a persistent, versioned coordinate stack.

*   **WorldHistoryManager:** Stores the absolute intent of the world.
*   **WorldReconciler:** A high-performance engine that compares intent vs reality and dispatches the swarm.
*   **Deterministic Undo/Redo:** By popping/pushing state stacks per coordinate, the system can revert any sequence of actions back to the spawn state.

## 2. Drone AI & Swarm Management
Drones utilize a custom Finite State Machine (FSM) for high-precision physical interaction. Drones are now **Intent-Aware**, meaning they abort tasks if the ledger changes mid-flight.

## 2. Multiblock Systems
GhostLib implements a lightweight Multiblock API for industrial machines.

### Drone Port
*   **Controller-Member Pattern:** A central `DronePortController` manages 8 `DronePortMember` blocks.
*   **Structural Validation:** Logic checks for a valid 3x3 layout before enabling deployment features.
*   **Energy Consumption:** Integrated with VoltLink to require Flux potential for drone manufacturing and dispatch.

## 3. The Blueprint Engine
The Blueprint system allows for arbitrary structure projection.

*   **Pattern NBT:** Structures are stored as a list of relative `BlockPos` and `BlockState` in the item's `CUSTOM_DATA`.
*   **Projection:** Holding a Blueprint item triggers a client-side rendering of the structure using `GhostPlacerItemRenderer`.
*   **Persistence:** Once placed, Blueprints are converted into `GhostBlock` entities that retain the target state until built.

## 4. Configuration Layer
A flexible configuration system handles mod tuning without code changes.

*   **YAML Mapping:** `GhostLibConfig` reads from `config/*.yml`.
*   **Dynamic Defaults:** Default resources are extracted from the JAR if the config folder is missing.
*   **Values:** Covers everything from `DRONE_MAX_HEALTH` to `PORT_ACTIVATION_RANGE`.

## 5. Networking & Performance
*   **Spatial Partitioning:** `GhostJobManager` partitions tasks by Chunk (`long key`). Search complexity is O(R²) where R is search radius.
*   **Lazy Syncing:** Deconstruction markers use a `dirty` flag to minimize packet overhead.
*   **Atomic Transactions:** `GhostHistoryManager` groups placements into batches for stable Undos.
