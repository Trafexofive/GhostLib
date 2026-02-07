package com.example.ghostlib.integration.kubejs;

import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.util.LogisticsNetworkManager;
import com.example.factorycore.power.FactoryNetworkManager;
import net.minecraft.world.level.Level;

public class GhostJS {
    public static GhostJobManager jobs(Level level) {
        return GhostJobManager.get(level);
    }
    
    public static LogisticsNetworkManager logistics(Level level) {
        return LogisticsNetworkManager.get(level);
    }
    
    public static FactoryNetworkManager power(Level level) {
        return FactoryNetworkManager.get(level);
    }
}
