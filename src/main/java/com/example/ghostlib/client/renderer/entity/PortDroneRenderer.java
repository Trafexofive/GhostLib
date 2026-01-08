package com.example.ghostlib.client.renderer.entity;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.client.model.DroneModel;
import com.example.ghostlib.entity.PortDroneEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class PortDroneRenderer extends MobRenderer<PortDroneEntity, DroneModel<PortDroneEntity>> {
    private static final ResourceLocation DRONE_TEXTURE = ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "textures/entity/drone.png");

    public PortDroneRenderer(EntityRendererProvider.Context context) {
        super(context, new DroneModel<>(context.bakeLayer(DroneRenderer.DRONE_LAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(PortDroneEntity entity) {
        return DRONE_TEXTURE;
    }
}
