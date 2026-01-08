package com.example.ghostlib.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.entity.PathfinderMob;

public class DroneModel<T extends PathfinderMob> extends EntityModel<T> {
    private final ModelPart body;

    public DroneModel(ModelPart root) {
        this.body = root.getChild("body");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // The "Brain" Core (Central cube)
        PartDefinition body = partdefinition.addOrReplaceChild("body", 
            CubeListBuilder.create().texOffs(0, 0).addBox(-3.0F, -2.0F, -3.0F, 6.0F, 4.0F, 6.0F, new CubeDeformation(0.0F)), 
            PartPose.offset(0.0F, 20.0F, 0.0F));

        // The Outer Carbon Ring (Simulating roundness/frame)
        body.addOrReplaceChild("frame",
            CubeListBuilder.create().texOffs(0, 10).addBox(-4.0F, -1.0F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.1F)),
            PartPose.offset(0.0F, 0.0F, 0.0F));

        // Corner Thrusters/Sensors (adding mechanical detail)
        for (int i = 0; i < 4; i++) {
            float x = (i % 2 == 0) ? 3.5F : -3.5F;
            float z = (i < 2) ? 3.5F : -3.5F;
            body.addOrReplaceChild("thruster_" + i,
                CubeListBuilder.create().texOffs(32, 0).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offset(x, 0.0F, z));
        }

        return LayerDefinition.create(meshdefinition, 64, 32);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
        body.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}