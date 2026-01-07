package com.example.ghostlib.client.renderer.entity;

import com.example.ghostlib.GhostLib;
import com.example.ghostlib.client.model.DroneModel;
import com.example.ghostlib.entity.DroneEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class DroneRenderer extends MobRenderer<DroneEntity, DroneModel> {
    public static final ModelLayerLocation DRONE_LAYER = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "drone"), "main");
    private static final ResourceLocation DRONE_TEXTURE = ResourceLocation.fromNamespaceAndPath(GhostLib.MODID, "textures/entity/drone.png");

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context, new DroneModel(context.bakeLayer(DRONE_LAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(DroneEntity entity) {
        return DRONE_TEXTURE;
    }
}
