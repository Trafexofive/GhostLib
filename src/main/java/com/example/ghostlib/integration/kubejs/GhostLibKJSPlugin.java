package com.example.ghostlib.integration.kubejs;

import com.example.ghostlib.util.GhostJobManager;
import com.example.ghostlib.util.LogisticsNetworkManager;
import com.example.ghostlib.block.entity.GhostBlockEntity;
import com.example.factorycore.power.FactoryNetworkManager;
import com.example.factorycore.power.ElectricalNetwork;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;

public class GhostLibKJSPlugin implements KubeJSPlugin {
    @Override
    public void registerBindings(BindingRegistry event) {
        event.add("GhostJobManager", GhostJobManager.class);
        event.add("LogisticsNetworkManager", LogisticsNetworkManager.class);
        event.add("FactoryNetworkManager", FactoryNetworkManager.class);
        event.add("ElectricalNetwork", ElectricalNetwork.class);
        event.add("GhostState", GhostBlockEntity.GhostState.class);
        event.add("JobType", GhostJobManager.JobType.class);
        event.add("GhostJS", GhostJS.class);
    }
}