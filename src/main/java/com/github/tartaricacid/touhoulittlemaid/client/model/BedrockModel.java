package com.github.tartaricacid.touhoulittlemaid.client.model;

import com.github.tartaricacid.touhoulittlemaid.client.animation.CustomJsAnimationManger;
import com.github.tartaricacid.touhoulittlemaid.client.animation.inner.IAnimation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.EntityChairWrapper;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.EntityMaidWrapper;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;
import com.github.tartaricacid.touhoulittlemaid.client.model.pojo.BedrockModelPOJO;
import com.github.tartaricacid.touhoulittlemaid.client.model.pojo.BonesItem;
import com.github.tartaricacid.touhoulittlemaid.client.model.pojo.CubesItem;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityChair;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.model.EntityModel;
import net.minecraft.client.renderer.entity.model.IHasArm;
import net.minecraft.client.renderer.entity.model.IHasHead;
import net.minecraft.client.renderer.model.ModelRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.HandSide;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.script.Invocable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class BedrockModel<T extends LivingEntity> extends EntityModel<T> implements IHasArm, IHasHead {
    public final AxisAlignedBB renderBoundingBox;
    /**
     * 存储 ModelRender 子模型的 HashMap
     */
    protected final HashMap<String, ModelRendererWrapper> modelMap = new HashMap<>();
    /**
     * 存储 Bones 的 HashMap，主要是给后面寻找父骨骼进行坐标转换用的
     */
    private final HashMap<String, BonesItem> indexBones = new HashMap<>();
    /**
     * 哪些模型需要渲染。加载进父骨骼的子骨骼是不需要渲染的
     */
    private final List<ModelRenderer> shouldRender = new LinkedList<>();
    /**
     * 用于自定义动画的变量
     */
    private final EntityMaidWrapper entityMaidWrapper = new EntityMaidWrapper();
    private final EntityChairWrapper entityChairWrapper = new EntityChairWrapper();
    private List<Object> animations = Lists.newArrayList();
    private List<ModelRenderer> cubes = Lists.newArrayList();

    public BedrockModel() {
        super(RenderType::entityTranslucent);
        renderBoundingBox = new AxisAlignedBB(-1, 0, -1, 1, 2, 1);
    }

    public BedrockModel(BedrockModelPOJO pojo) {
        super(RenderType::entityTranslucent);

        // 材质的长度、宽度
        texWidth = pojo.getGeometryModel().getTexturewidth();
        texHeight = pojo.getGeometryModel().getTextureheight();

        List<Float> offset = pojo.getGeometryModel().getVisibleBoundsOffset();
        float offsetX = offset.get(0);
        float offsetY = offset.get(1);
        float offsetZ = offset.get(2);
        float width = pojo.getGeometryModel().getVisibleBoundsWidth() / 2.0f;
        float height = pojo.getGeometryModel().getVisibleBoundsHeight() / 2.0f;
        renderBoundingBox = new AxisAlignedBB(offsetX - width, offsetY - height, offsetZ - width, offsetX + width, offsetY + height, offsetZ + width);

        // 往 indexBones 里面注入数据，为后续坐标转换做参考
        for (BonesItem bones : pojo.getGeometryModel().getBones()) {
            // 塞索引，这是给后面坐标转换用的
            indexBones.put(bones.getName(), bones);
            // 塞入新建的空 ModelRenderer 实例
            // 因为后面添加 parent 需要，所以先塞空对象，然后二次遍历再进行数据存储
            modelMap.put(bones.getName(), new ModelRendererWrapper(new ModelRenderer(this)));
        }

        // 开始往 ModelRenderer 实例里面塞数据
        for (BonesItem bones : pojo.getGeometryModel().getBones()) {
            // 骨骼名称，注意因为后面动画的需要，头部、手部、腿部等骨骼命名必须是固定死的
            String name = bones.getName();
            // 旋转点，可能为空
            @Nullable List<Float> rotation = bones.getRotation();
            // 父骨骼的名称，可能为空
            @Nullable String parent = bones.getParent();
            // 塞进 HashMap 里面的模型对象
            ModelRenderer model = modelMap.get(name).getModelRenderer();

            // 镜像参数
            model.mirror = bones.isMirror();

            // 旋转点
            model.setPos(convertPivot(bones, 0), convertPivot(bones, 1), convertPivot(bones, 2));

            // Nullable 检查，设置旋转角度
            if (rotation != null) {
                setRotationAngle(model, convertRotation(rotation.get(0)), convertRotation(rotation.get(1)), convertRotation(rotation.get(2)));
            }

            // Null 检查，进行父骨骼绑定
            if (parent != null) {
                modelMap.get(parent).getModelRenderer().addChild(model);
            } else {
                // 没有父骨骼的模型才进行渲染
                shouldRender.add(model);
            }

            // 我的天，Cubes 还能为空……
            if (bones.getCubes() == null) {
                continue;
            }

            // 塞入 Cube List
            for (CubesItem cubes : bones.getCubes()) {
                List<Float> uv = cubes.getUv();
                List<Float> size = cubes.getSize();
                boolean mirror = cubes.isMirror();
                float inflate = cubes.getInflate();

                model.cubes.add(new ModelFloatBox(uv.get(0), uv.get(1),
                        convertOrigin(bones, cubes, 0), convertOrigin(bones, cubes, 1), convertOrigin(bones, cubes, 2),
                        size.get(0), size.get(1), size.get(2), inflate, inflate, inflate, mirror,
                        texWidth, texHeight));
            }
        }
    }

    @Override
    @ParametersAreNonnullByDefault
    public void setupAnim(T entityIn, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        if (animations != null) {
            Invocable invocable = (Invocable) CustomJsAnimationManger.NASHORN;
            if (entityIn instanceof EntityMaid) {
                setupMaidAnim((EntityMaid) entityIn, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, invocable);
                return;
            }
            if (entityIn instanceof EntityChair) {
                setupChairAnim((EntityChair) entityIn, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, invocable);
            }
        }
    }

    @Override
    @ParametersAreNonnullByDefault
    public void renderToBuffer(MatrixStack matrixStack, IVertexBuilder buffer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        for (ModelRenderer model : shouldRender) {
            model.render(matrixStack, buffer, packedLight, packedOverlay);
        }
    }


    @SuppressWarnings("unchecked")
    private void setupMaidAnim(EntityMaid entityIn, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, Invocable invocable) {
        for (Object animation : animations) {
            if (animation instanceof IAnimation) {
                ((IAnimation<EntityMaid>) animation).setRotationAngles(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, 0, entityIn, modelMap);
            } else {
                entityMaidWrapper.setData(entityIn, attackTime, riding);
                String modelId = (entityIn).getModelId();
                try {
                    invocable.invokeMethod(animation, "animation",
                            entityMaidWrapper, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, 0.0625f, modelMap);
                } catch (Exception e) {
                    e.printStackTrace();
                    CustomPackLoader.MAID_MODEL.removeAnimation(modelId);
                }
                entityMaidWrapper.clearData();
            }
        }
        // TODO: 可能需要修改
        if (entityIn.isSleep()) {
            GL11.glRotated(180, 0, 1, 0);
            GL11.glRotated(-90, 1, 0, 0);
            GL11.glTranslated(0, -1.08, 1.3);
        }
    }

    @SuppressWarnings("unchecked")
    private void setupChairAnim(EntityChair entityIn, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, Invocable invocable) {
        for (Object animation : animations) {
            if (animation instanceof IAnimation) {
                ((IAnimation<EntityChair>) animation).setRotationAngles(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, 0, entityIn, modelMap);
            } else {
                entityChairWrapper.setData(entityIn);
                String modelId = (entityIn).getModelId();
                try {
                    invocable.invokeMethod(animation, "animation",
                            entityChairWrapper, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, 0.0625f, modelMap);
                } catch (Exception e) {
                    e.printStackTrace();
                    CustomPackLoader.CHAIR_MODEL.removeAnimation(modelId);
                }
                entityChairWrapper.clearData();
            }
        }
    }

    private void setRotationAngle(ModelRenderer modelRenderer, float x, float y, float z) {
        modelRenderer.xRot = x;
        modelRenderer.yRot = y;
        modelRenderer.zRot = z;
    }

    @Override
    public void translateToHand(HandSide sideIn, MatrixStack matrixStackIn) {
        ModelRenderer arm = getArm(sideIn);
        if (arm != null) {
            arm.translateAndRotate(matrixStackIn);
        }
    }

    @Nullable
    private ModelRenderer getArm(HandSide sideIn) {
        return sideIn == HandSide.LEFT ? modelMap.get("armLeft").getModelRenderer() : modelMap.get("armRight").getModelRenderer();
    }

    public boolean hasHead() {
        return modelMap.containsKey("head");
    }

    @Override
    public ModelRenderer getHead() {
        return modelMap.get("head").getModelRenderer();
    }

    public boolean hasLeftArm() {
        return modelMap.containsKey("armLeft");
    }

    public boolean hasRightArm() {
        return modelMap.containsKey("armRight");
    }

    public boolean hasArmPositioningModel(HandSide side) {
        ModelRendererWrapper arm = (side == HandSide.LEFT ? modelMap.get("armLeftPositioningBone") : modelMap.get("armRightPositioningBone"));
        return arm != null;
    }

    public void translateToPositioningHand(HandSide sideIn, MatrixStack matrixStackIn) {
        ModelRenderer arm = (sideIn == HandSide.LEFT ? modelMap.get("armLeftPositioningBone").getModelRenderer() : modelMap.get("armRightPositioningBone").getModelRenderer());
        if (arm != null) {
            arm.translateAndRotate(matrixStackIn);
        }
    }

    public ModelRenderer getRandomModelPart(Random randomIn) {
        return this.cubes.get(randomIn.nextInt(this.cubes.size()));
    }

    @Override
    public void accept(ModelRenderer modelRenderer) {
        if (this.cubes == null) {
            this.cubes = Lists.newArrayList();
        }
        this.cubes.add(modelRenderer);
    }

    /**
     * 基岩版的旋转中心计算方式和 Java 版不太一样，需要进行转换
     * <p>
     * 如果有父模型
     * <li>x，z 方向：本模型坐标 - 父模型坐标
     * <li>y 方向：父模型坐标 - 本模型坐标
     * <p>
     * 如果没有父模型
     * <li>x，z 方向不变
     * <li>y 方向：24 - 本模型坐标
     *
     * @param index 是 xyz 的哪一个，x 是 0，y 是 1，z 是 2
     */
    private float convertPivot(BonesItem bones, int index) {
        if (bones.getParent() != null) {
            if (index == 1) {
                return indexBones.get(bones.getParent()).getPivot().get(index) - bones.getPivot().get(index);
            } else {
                return bones.getPivot().get(index) - indexBones.get(bones.getParent()).getPivot().get(index);
            }
        } else {
            if (index == 1) {
                return 24 - bones.getPivot().get(index);
            } else {
                return bones.getPivot().get(index);
            }
        }
    }

    /**
     * 基岩版和 Java 版本的方块起始坐标也不一致，Java 是相对坐标，而且 y 值方向不一致。
     * 基岩版是绝对坐标，而且 y 方向朝上。
     * 其实两者规律很简单，但是我找了一下午，才明白咋回事。
     * <li>如果是 x，z 轴，那么只需要方块起始坐标减去旋转点坐标
     * <li>如果是 y 轴，旋转点坐标减去方块起始坐标，再减去方块的 y 长度
     *
     * @param index 是 xyz 的哪一个，x 是 0，y 是 1，z 是 2
     */
    private float convertOrigin(BonesItem bones, CubesItem cubes, int index) {
        if (index == 1) {
            return bones.getPivot().get(index) - cubes.getOrigin().get(index) - cubes.getSize().get(index);
        } else {
            return cubes.getOrigin().get(index) - bones.getPivot().get(index);
        }
    }

    /**
     * 基岩版用的是度，Java 版用的是弧度，这个转换很简单
     */
    private float convertRotation(float degree) {
        return (float) (degree * Math.PI / 180);
    }

    public void setAnimations(List<Object> animations) {
        this.animations = animations;
    }
}
