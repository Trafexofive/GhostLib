# 🏛️ THE COMMAND LEDGER ARCHITECTURE

GhostLib implements a deterministic **Coordinate-State Stack Ledger** to manage world changes. This system ensures high-fidelity construction, deconstruction, and a mathematically perfect Undo/Redo timeline.

## 1. Core Axioms

1.  **Zero Instant Magic:** Code never directly places or breaks real blocks. All physical changes are delegated to the Drone Swarm.
2.  **Ephemerality of Intent:** Ghost markers (`GhostBlock`) are non-physical and can be added/removed instantly to reflect current intent.
3.  **Source of Truth:** The `WorldHistoryManager` ledger is the absolute authority. The physical world is merely a state that eventually reconciles with the ledger.

## 2. The Version Stack (Lineage)

Every coordinate (`BlockPos`) touched during a session maintains a versioned stack of `BlockSnapshot` objects:

*   **Version 0 (Root):** The "Natural" state of the block (spawn state) captured at the first interaction.
*   **Version N:** The current desired state pushed by a player (manual or blueprint).
*   **Undo:** Pops the top of the stack.
*   **Redo:** Pushes a previously popped state back onto the stack.

## 3. Reconciliation Lifecycle

The `WorldReconciler` engine constantly compares **Ledger Intent** vs **Physical Reality**:

| Intent | Reality | Action |
| :--- | :--- | :--- |
| **AIR** | **AIR** | Clean. |
| **AIR** | **BLOCK** | Issue **Deconstruction Job**. Drone will physically clear it. |
| **BLOCK** | **AIR** | Place **Ghost Marker**. Drone will physically build it. |
| **BLOCK** | **BLOCK** | If states match: Clean. If different: Issue **Deconstruction Job** first. |

## 4. Performance & Scalability

*   **Dirty-Set Reconciliation:** Only coordinates with modified intent are scanned. This prevents O(N) tick lag in large factories.
*   **Persistent Stacks:** All stacks, including the global undo/redo timeline, are saved to `level.dat`. History survives restarts.
*   **Actor Filtering:** Ledger only records changes from `Player` actors. Drone building and Reconciler ghost placement are ignored to prevent feedback loops.

## 5. Technical Components

*   `WorldHistoryManager`: Persistent storage of coordinate stacks and global timeline.
*   `WorldReconciler`: The bridge that issues Drone Jobs based on ledger deltas.
*   `BlockSnapshot`: High-fidelity data structure storing `BlockState` and `BlockEntity` NBT.
*   `DroneEntity`: Re-evaluates current tasks against the ledger every 10 ticks for safe mid-action interrupts.
