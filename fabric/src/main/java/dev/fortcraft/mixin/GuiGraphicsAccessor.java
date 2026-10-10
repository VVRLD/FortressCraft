package dev.fortcraft.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets Overlay draw TF2's picture with a premultiplied-alpha pipeline (the public blit fixes the pipeline). */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsAccessor {
	@Invoker("innerBlit")
	void fortcraft$innerBlit(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler, int x0, int y0, int x1, int y1,
		float u0, float u1, float v0, float v1, int color);
}
