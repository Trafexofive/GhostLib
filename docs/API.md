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

## 2. The Blueprint System
Custom items can extend `BlueprintItem` to provide pre-configured structure patterns.

```java
public class MyBlueprint extends BlueprintItem {
    public MyBlueprint() {
        super(BlueprintType.CUSTOM, new Item.Properties());
    }
    
    @Override
    protected void setupPattern(ItemStack stack) {
        // Populate "Pattern" NBT list with relative positions and states
    }
}
```

## 3. Swarm Coordination
Drone Ports interact with the `GhostJobManager` to find work.

```java
// Find nearest job for a port
Job work = GhostJobManager.get(level).findNearestJob(portPos, radius);
```

## 4. Custom Drone Behavior
Drones are `PathfinderMob` entities. You can extend `DroneEntity` or apply custom NBT data to modify their speed or health.
*   **Speed:** Controlled by `Attributes.MOVEMENT_SPEED`.
*   **Flight:** Drones have `noPhysics = true` and `setNoGravity(true)`.
