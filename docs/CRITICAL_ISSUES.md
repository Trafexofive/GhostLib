# GhostLib Critical Issues Report
**Generated:** 2026-01-28  
**Status:** 🔴 NEEDS IMMEDIATE ATTENTION

---

## 🚨 CRITICAL BUGS (Fix Immediately)

### 1. **Race Condition in DroneEntity.tick() - Line 140**
**Severity:** 🔴 CRITICAL  
**Location:** `DroneEntity.java:140`

```java
if (!GhostJobManager.get(level()).isAssignedTo(currentJob.pos(), this.getUUID())) {
    this.currentJob = null;
    this.droneState = isInventoryEmpty() ? DroneState.IDLE : DroneState.DUMPING_ITEMS;
}
```

**Problem:**  
- Job can be released by JobManager between the check and the state change
- No synchronization between drone and job manager
- Can lead to orphaned jobs or double-assignment

**Impact:** Drones may lose jobs mid-flight, causing stuck states

---

### 2. **Null Pointer Risk in handleFindingJob() - Line 237**
**Severity:** 🔴 CRITICAL  
**Location:** `DroneEntity.java:237-240`

```java
if (level().getBlockEntity(job.pos()) instanceof GhostBlockEntity gbe) {
    gbe.setAssignedTo(this.getUUID());
    gbe.setState(GhostBlockEntity.GhostState.INCOMING);
}
```

**Problem:**  
- Block entity can be null or unloaded
- No validation that the ghost block still exists
- Chunk could unload between job assignment and this call

**Impact:** NullPointerException crash

---

### 3. **Memory Leak: Jobs Not Cleaned on Chunk Unload**
**Severity:** 🔴 CRITICAL  
**Location:** `GhostJobManager.java` (missing handler)

**Problem:**  
- No ChunkEvent.Unload listener
- Jobs remain in ConcurrentHashMaps forever
- Drones can claim jobs in unloaded chunks

**Impact:** Memory leak over time, performance degradation

---

### 4. **No SavedData Implementation**
**Severity:** 🔴 CRITICAL  
**Location:** `GhostJobManager.java` (missing)

**Problem:**  
- Jobs are volatile, lost on server restart
- Ghost blocks re-register but assignments are lost
- Drones lose their current jobs

**Impact:** All construction progress lost on restart

---

### 5. **Unsafe Player Inventory Modification - Line 344**
**Severity:** 🟠 HIGH  
**Location:** `DroneEntity.java:344`

```java
ItemStack taken = stackInSlot.split(1);
this.inventory.addItem(taken);
```

**Problem:**  
- Modifies player inventory without permission check
- No transaction/rollback mechanism
- Can cause item duplication if addItem fails

**Impact:** Item duplication exploit

---

## 🟠 HIGH PRIORITY ISSUES

### 6. **Port Validation Missing - Line 131**
**Severity:** 🟠 HIGH  
**Location:** `DroneEntity.java:131-136`

```java
if (!(level().getBlockEntity(p.get()) instanceof IDronePort)) {
    Block.popResource(level(), blockPosition(), new ItemStack(ModItems.DRONE_SPAWN_EGG.get()));
    this.discard();
    return;
}
```

**Problem:**  
- No check if chunk is loaded
- `getBlockEntity()` can return null
- Drone discards itself without cleanup

**Impact:** Drones disappear, jobs orphaned

---

### 7. **Energy System Has No Bounds Checking**
**Severity:** 🟠 HIGH  
**Location:** `DroneEntity.java:176`

```java
this.energy += charged;
if (this.energy >= MAX_ENERGY) {
    this.droneState = DroneState.IDLE;
}
```

**Problem:**  
- Energy can overflow MAX_ENERGY
- No cap on energy value
- Can cause integer overflow

**Impact:** Drones with infinite energy

---

### 8. **Concurrent Modification in GhostJobManager**
**Severity:** 🟠 HIGH  
**Location:** `GhostJobManager.java:findInMap()`

**Problem:**  
- Iterating over ConcurrentHashMap while other threads modify
- No snapshot or locking
- Can throw ConcurrentModificationException

**Impact:** Server crash

---

### 9. **No Validation of targetAfter BlockState**
**Severity:** 🟠 HIGH  
**Location:** `GhostBlockEntity.java:76-82`

```java
public void setTargetState(BlockState state) {
    this.targetState = state;
    sync();
}
```

**Problem:**  
- No null check
- No validation that block is placeable
- Can set invalid states

**Impact:** Crashes when trying to place invalid blocks

---

### 10. **Drone Inventory Not Persisted**
**Severity:** 🟠 HIGH  
**Location:** `DroneEntity.java` (missing NBT save/load)

**Problem:**  
- SimpleContainer inventory is never saved
- Items lost on world reload
- No NBT serialization for drone state

**Impact:** Item loss on restart

---

## 🟡 MEDIUM PRIORITY ISSUES

### 11. **Inefficient Container Search - Line 370**
**Severity:** 🟡 MEDIUM  
**Location:** `DroneEntity.java:370`

```java
for (BlockPos pos : BlockPos.betweenClosed(center.offset(-rh, -rv, -rh), center.offset(rh, rv, rh))) {
```

**Problem:**  
- Searches every block in a cube
- No caching or spatial indexing
- Called every tick when fetching

**Impact:** Performance degradation with large search ranges

---

### 12. **No Timeout for Stuck Drones**
**Severity:** 🟡 MEDIUM  
**Location:** `DroneEntity.java` (missing)

**Problem:**  
- Drones can get stuck in states forever
- No watchdog timer
- No automatic reset

**Impact:** Drones become permanently stuck

---

### 13. **Missing Client-Server Sync**
**Severity:** 🟡 MEDIUM  
**Location:** `DroneEntity.java` (missing)

**Problem:**  
- droneState is not synced to clients
- currentJob is not synced
- Clients can't render drone state accurately

**Impact:** Visual glitches, desynced animations

---

### 14. **No Bounds Checking on Movement**
**Severity:** 🟡 MEDIUM  
**Location:** `DroneEntity.java:573-583`

**Problem:**  
- No world border check
- Can pathfind into unloaded chunks
- No Y-level validation

**Impact:** Drones can fly into void or unloaded areas

---

### 15. **Hardcoded Magic Numbers**
**Severity:** 🟡 MEDIUM  
**Location:** Throughout `DroneEntity.java`

**Examples:**
- Line 223: `if (this.tickCount % 20 == 0)`
- Line 289: `this.waitTicks = 40;`
- Line 360: `waitTicks = 100;`

**Problem:**  
- Not configurable
- Inconsistent timing
- Hard to tune

**Impact:** Poor gameplay balance

---

## 🔵 LOW PRIORITY (Code Quality)

### 16. **Missing JavaDoc**
- Most methods lack documentation
- Complex FSM logic not explained
- No parameter descriptions

### 17. **Long Methods**
- `handleFindingJob()`: 76 lines
- `handleTravelingFetch()`: 62 lines
- Needs refactoring

### 18. **Inconsistent Error Handling**
- Some methods return null
- Others reset to idle
- No consistent pattern

### 19. **No Logging**
- No debug logging for state transitions
- Hard to troubleshoot issues
- No performance metrics

### 20. **Missing Unit Tests**
- Zero test coverage
- No FSM state transition tests
- No edge case validation

---

## 🎯 Immediate Action Items

### **THIS WEEK:**
1. ✅ Add chunk unload event handler
2. ✅ Implement SavedData for GhostJobManager
3. ✅ Add null checks in all BlockEntity accesses
4. ✅ Fix energy overflow bug
5. ✅ Add drone inventory NBT persistence

### **NEXT WEEK:**
6. ✅ Refactor handleFindingJob() into smaller methods
7. ✅ Add synchronization to job claiming
8. ✅ Implement drone timeout/watchdog
9. ✅ Add comprehensive logging
10. ✅ Fix player inventory transaction safety

### **MONTH 1:**
11. ✅ Write unit tests for all core systems
12. ✅ Add client-server sync for drone state
13. ✅ Optimize container search with caching
14. ✅ Extract magic numbers to config
15. ✅ Complete JavaDoc coverage

---

## 📊 Risk Assessment

| Issue | Severity | Likelihood | Impact | Priority |
|-------|----------|------------|--------|----------|
| Chunk Unload Leak | Critical | High | High | P0 |
| SavedData Missing | Critical | High | High | P0 |
| Null Pointer Crashes | Critical | Medium | High | P0 |
| Race Conditions | Critical | Medium | High | P0 |
| Item Duplication | High | Low | High | P1 |
| Energy Overflow | High | Low | Medium | P1 |
| Concurrent Modification | High | Medium | High | P1 |
| Drone Inventory Loss | High | High | Medium | P1 |

---

## 🛡️ Hardening Checklist

- [ ] All null checks added
- [ ] Chunk load validation everywhere
- [ ] SavedData implemented
- [ ] Energy bounds checking
- [ ] Inventory persistence
- [ ] Transaction safety for player inventory
- [ ] Chunk unload cleanup
- [ ] Synchronization for job claiming
- [ ] Timeout mechanisms
- [ ] Comprehensive logging
- [ ] Unit test coverage \u003e 80%
- [ ] Integration tests passing
- [ ] 24-hour stress test passed

---

**Next Step:** Start with P0 issues (1-4) immediately.
