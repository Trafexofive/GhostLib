# NeoForge Mod Boilerplate Guide (1.21.1)

This guide outlines the standard setup for creating a new NeoForge mod that integrates with **GhostLib**.

## 1. File Structure
A standard NeoForge project follows the Maven directory structure:

```text
MyMod/
├── build.gradle
├── gradle.properties
├── settings.gradle
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── example/
        │           └── mymod/
        │               ├── MyMod.java          // Entry point
        │               └── registry/           // DeferredRegisters
        └── resources/
            ├── META-INF/
            │   └── neoforge.mods.toml      // Mod metadata
            ├── assets/                     // Textures, models, lang
            └── data/                       // Recipes, tags, loot tables
```

## 2. Gradle Configuration

### `gradle.properties`
Define your versions here to keep `build.gradle` clean.

```properties
minecraft_version=1.21.1
minecraft_version_range=[1.21.1,1.22)
neo_version=21.1.217
neo_version_range=[21.1.65-beta,)
loader_version_range=[4,)
mod_id=mymod
mod_name=My Mod
mod_license=All Rights Reserved
mod_version=1.0.0
mod_group_id=com.example
mod_authors=YourName
```

### `build.gradle`
The build script using the `net.neoforged.moddev` plugin.

```groovy
plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '1.0.18'
}

tasks.named('wrapper', Wrapper).configure {
    distributionType = Wrapper.DistributionType.BIN
}

version = project.mod_version
group = project.mod_group_id

repositories {
    mavenLocal() // For GhostLib if published locally
    maven {
        name = "NeoForged"
        url = "https://maven.neoforged.net/releases"
    }
}

base {
    archivesName = project.mod_id
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21) // MC 1.21 requires Java 21
}

neoForge {
    version = project.neo_version

    parchment {
        mappingsVersion = '2024.07.28'
        minecraftVersion = '1.21'
    }

    runs {
        client {
            client()
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
    }

    mods {
        "${project.mod_id}" {
            sourceSet(sourceSets.main)
        }
    }
}

dependencies {
    // Depend on GhostLib (ensure it is built/published or included in composite)
    implementation 'com.example:ghostlib:1.0.1'
}
```

## 3. Metadata (`neoforge.mods.toml`)
Located in `src/main/resources/META-INF/`. This defines your mod's dependencies and info.

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description="Description goes here."

[[dependencies.mymod]]
modId="neoforge"
type="required"
versionRange="${neo_version_range}"
ordering="NONE"
side="BOTH"

[[dependencies.mymod]]
modId="minecraft"
type="required"
versionRange="${minecraft_version_range}"
ordering="NONE"
side="BOTH"

# Dependency on GhostLib
[[dependencies.mymod]]
modId="ghostlib"
type="required"
versionRange="[1.0.0,)"
ordering="AFTER"
side="BOTH"
```

## 4. The Main Class
The entry point of your mod.

```java
package com.example.mymod;

import com.example.mymod.registry.ModBlocks;
import com.example.mymod.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(MyMod.MODID)
public class MyMod {
    public static final String MODID = "mymod";

    public MyMod(IEventBus modEventBus) {
        // Register DeferredRegisters
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
    }
}
```

## 5. Registration (DeferredRegister)
The standard way to register content in NeoForge.

### `ModBlocks.java`
```java
package com.example.mymod.registry;

import com.example.mymod.MyMod;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MyMod.MODID);

    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(2.0f)));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
```

### `ModItems.java`
```java
package com.example.mymod.registry;

import com.example.mymod.MyMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MyMod.MODID);

    // Register a BlockItem for the block
    public static final DeferredItem<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block",
            () -> new BlockItem(ModBlocks.EXAMPLE_BLOCK.get(), new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
```
