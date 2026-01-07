# GhostLib API Documentation

GhostLib is designed to be extensible. You can programmatically register jobs and interact with drones via the `GhostJobManager`.

## 1. Registering Jobs

### Construction Jobs
To place a ghost that a drone will eventually build:
```java
BlockPos pos = new BlockPos(100, 64, 100);
BlockState target = Blocks.IRON_BLOCK.defaultBlockState();

// 1. Place the Ghost Block
level.setBlock(pos, ModBlocks.GHOST_BLOCK.get().defaultBlockState(), 3);

// 2. Configure the Block Entity
if (level.getBlockEntity(pos) instanceof GhostBlockEntity gbe) {
    gbe.setTargetState(target);
    gbe.setState(GhostBlockEntity.GhostState.UNASSIGNED); // This registers the job automatically
}
```

### Direct Deconstruction
To mark a solid block for removal (by a drone):
```java
BlockPos pos = new BlockPos(100, 64, 100);
GhostJobManager.get(level).registerDirectDeconstruct(pos, Blocks.AIR.defaultBlockState(), level);
```

## 2. Querying Job State
You can check if a position is currently being worked on:
```java
UUID droneId = ...;
boolean isBusy = GhostJobManager.get(level).isAssignedTo(pos, droneId);
```

## 3. Custom Drone Behavior
Drones are `PathfinderMob` entities. You can extend `DroneEntity` or apply custom NBT data to modify their speed or health.
*   **Speed:** Controlled by `Attributes.MOVEMENT_SPEED`.
*   **Flight:** Drones have `noPhysics = true` and `setNoGravity(true)`.
