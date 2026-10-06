package dev.fortcraft;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.fortcraft.link.FortLink;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * TF2's weapon and HUD, as a picture TF2 hands over (transparent where TF2 drew nothing), drawn
 * full-screen in place of Minecraft's own HUD. While it's showing, Minecraft's HUD and hand are
 * hidden (see the mixins).
 *
 * Two ways in: on the GPU (shared Direct3D textures, see {@link GpuOverlay}), or as pixels in
 * shared memory uploaded each frame (slower; used when the GPU way isn't available).
 */
public final class Overlay {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("fortcraft", "tf2_overlay");

	private static DynamicTexture texture;
	private static int width, height;
	private static int lastSeq, lastGpuSeq;
	private static boolean active;
	private static long frames;
	private static String path = "";

	private static long logStart, framesAtLog, copyNanos;

	/**
	 * Draw Minecraft's world with the look direction TF2 rendered the shown overlay with, so TF2's
	 * buildings, mobs' effects and projectiles stay planted in the world when the camera turns
	 * (otherwise they lag behind by the overlay's age). -Dfortcraft.noAnchor=true turns it off.
	 */
	public static final boolean ANCHOR = !Boolean.getBoolean("fortcraft.noAnchor");
	private static boolean havePose;
	private static float poseYaw, posePitch;
	private static boolean havePosePosition;
	private static double poseX, poseY, poseZ;
	private static int shownSlot;
	// Measurement, logged with perf: how far Minecraft's own look was from the overlay's look.
	private static double driftSum, driftMax, ageSum;
	private static long driftSamples;

	private Overlay() {
	}

	/** True while TF2 is linked and has sent at least one overlay frame. */
	public static boolean active() {
		return active;
	}

	/** Call once per render frame, before drawing. */
	public static void update(Minecraft minecraft, boolean guestAlive) {
		active = guestAlive && texture != null;
		if (!guestAlive) {
			return;
		}
		if (FortLink.gpuOverlayValid() && GpuOverlay.usable()) {
			updateFromGpu(minecraft);
			return;
		}

		int seq = FortLink.overlaySeq();
		if (seq == lastSeq) {
			return;
		}
		lastSeq = seq;
		int w = FortLink.overlayWidth(), h = FortLink.overlayHeight();
		if (!ensureTexture(minecraft, w, h)) {
			return;
		}
		long start = System.nanoTime();
		int front = FortLink.overlayFront();
		NativeImage image = texture.getPixels();
		FortLink.copyOverlay(MemorySegment.ofAddress(image.getPointer()).reinterpret((long) w * h * 4), (long) w * h * 4, front);
		texture.upload();
		takePose(front);
		gotFrame(minecraft, w, h, "shared memory", System.nanoTime() - start);
	}

	private static void updateFromGpu(Minecraft minecraft) {
		int seq = FortLink.gpuOverlaySeq();
		if (seq == lastGpuSeq) {
			return;
		}
		lastGpuSeq = seq;
		int w = FortLink.gpuOverlayWidth(), h = FortLink.gpuOverlayHeight();
		if (!ensureTexture(minecraft, w, h)) {
			return;
		}
		long start = System.nanoTime();
		int front = FortLink.gpuOverlayFront();
		if (GpuOverlay.copyInto(texture, front, FortLink.gpuOverlayGeneration(), w, h,
			FortLink.gpuOverlayHandle(0), FortLink.gpuOverlayHandle(1))) {
			takePose(front);
			gotFrame(minecraft, w, h, "GPU", System.nanoTime() - start);
		}
	}

	private static void takePose(int slot) {
		float[] pose = FortLink.overlayPose(slot);
		shownSlot = slot;
		poseYaw = pose[0];
		posePitch = pose[1];
		havePosePosition = Float.floatToRawIntBits(pose[5]) == 1;
		poseX = pose[2];
		poseY = pose[3];
		poseZ = pose[4];
		havePose = FortLink.overlayAgeMs(slot) >= 0;
	}

	/**
	 * The look to draw Minecraft's world with this frame: the overlay's, while anchoring. Also
	 * measures how far Minecraft's own look is from it. Returns null when not anchoring.
	 */
	public static float[] anchoredLook(float ownYaw, float ownPitch) {
		if (!active || !havePose) {
			return null;
		}
		double drift = Math.hypot(net.minecraft.util.Mth.wrapDegrees(ownYaw - poseYaw), ownPitch - posePitch);
		long age = FortLink.overlayAgeMs(shownSlot);
		driftSum += drift;
		driftMax = Math.max(driftMax, drift);
		ageSum += Math.max(0, age);
		driftSamples++;
		return ANCHOR ? new float[] { poseYaw, posePitch } : null;
	}

	/**
	 * The eye position to draw Minecraft's world from this frame: where TF2 stood when it drew the
	 * overlay on screen, while anchoring (otherwise strafing made TF2's picture slide). Null when
	 * not anchoring or not known.
	 */
	public static double[] anchoredPosition() {
		return ANCHOR && active && havePose && havePosePosition ? new double[] { poseX, poseY, poseZ } : null;
	}

	private static boolean ensureTexture(Minecraft minecraft, int w, int h) {
		if (w <= 0 || h <= 0 || w > FortLink.MAX_OVERLAY_W || h > FortLink.MAX_OVERLAY_H) {
			return false;
		}
		if (texture == null || w != width || h != height) {
			if (texture != null) {
				texture.close();
			}
			width = w;
			height = h;
			texture = new DynamicTexture(() -> "FortCraft TF2 overlay", new NativeImage(NativeImage.Format.RGBA, w, h, false));
			minecraft.getTextureManager().register(ID, texture);
			FortCraft.LOG.info("FortCraft: TF2 overlay {}x{}", w, h);
		}
		return true;
	}

	private static void gotFrame(Minecraft minecraft, int w, int h, String how, long nanos) {
		active = true;
		frames++;
		copyNanos += nanos;
		if (!how.equals(path)) {
			path = how;
			FortCraft.LOG.info("FortCraft: TF2 overlay now arriving via {}", how);
		}

		// Every 5 s: Minecraft's frame rate, new TF2 overlay frames per second, copy cost.
		long now = System.nanoTime();
		if (logStart == 0) {
			logStart = now;
			framesAtLog = frames;
		} else if (now - logStart >= 5_000_000_000L) {
			double seconds = (now - logStart) / 1e9;
			long n = frames - framesAtLog;
			FortCraft.LOG.info("FortCraft: perf {}x{} ({}): Minecraft {} fps, overlay {} new frames/s, copy {} ms each; "
				+ "overlay age {} ms, look drift avg {} max {} deg (anchor {})",
				w, h, how, minecraft.getFps(), String.format("%.0f", n / seconds), String.format("%.2f", copyNanos / 1e6 / Math.max(1, n)),
				String.format("%.1f", ageSum / Math.max(1, driftSamples)), String.format("%.2f", driftSum / Math.max(1, driftSamples)),
				String.format("%.1f", driftMax), ANCHOR ? "on" : "off");
			driftSum = driftMax = ageSum = 0;
			driftSamples = 0;
			logStart = now;
			framesAtLog = frames;
			copyNanos = 0;
		}
	}

	/** Draw it over the whole screen, smoothly scaled. Called from HudMixin in place of Minecraft's HUD. */
	public static void draw(GuiGraphicsExtractor graphics) {
		graphics.blit(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
			0, 0, graphics.guiWidth(), graphics.guiHeight(), 0.0F, 1.0F, 0.0F, 1.0F);
	}
}
