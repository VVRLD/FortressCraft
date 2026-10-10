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

	/**
	 * Frame pacing: before drawing, wait (briefly) until TF2 has published a new overlay frame, so
	 * every Minecraft frame shows exactly one new TF2 frame. Both games ran at about 240 fps on
	 * their own clocks, so roughly one Minecraft frame in twelve reused the previous TF2 frame (its
	 * camera pose and position too) and the next one jumped two: a stall-then-skip felt as jitter
	 * when moving (measured 2026-10-09: 8% of moving frames repeated). Only while the overlay
	 * arrives fast (100+ frames/s) and vsync is off. -Dfortcraft.noPacing=true turns it off.
	 */
	public static final boolean PACING = !Boolean.getBoolean("fortcraft.noPacing");
	private static final long MAX_WAIT_NANOS = 12_000_000L;
	private static double intervalNanos;  // smoothed time between new overlay frames
	private static long lastNewNanos;
	private static boolean newThisFrame;
	// Measurement, logged with perf: frames that showed no new TF2 frame, TF2 frames never shown,
	// time spent waiting, and waits that gave up.
	private static long repeatFrames, skippedFrames, waitNanos, waitTimeouts, pacedFrames;

	/**
	 * Round 2 (2026-10-09): waiting for "a new frame has arrived" still showed frames 2.4 to 5.9 ms
	 * apart, because TF2 publishes a frame only once the GPU has finished it, after a varying
	 * delay. The positions inside were evenly spaced (TF2 starts its frames evenly), so the motion
	 * on screen sped up and slowed down by about 25% from frame to frame. Now each frame is shown
	 * at the time TF2 started it plus a steady latency: the largest publish delay of the last
	 * second, plus a little. Frames then reach the screen as evenly as TF2 started them.
	 */
	private static final int DELAY_WINDOW = 240;
	private static final long[] delays = new long[DELAY_WINDOW];
	private static int delayCount, delayNext;
	private static long latencyNanos;
	private static long lateFrames, clockMismatch;
	private static double presentErrSum;
	private static long presentErrCount;
	private static long lastPresentNanos;
	private static double presentGapSum, presentGapSqSum;
	private static long presentGaps;

	/**
	 * Round 3 (2026-10-09): Alex's run after round 2 showed TF2's camera positions evenly spaced
	 * (step to step within 3%), but Minecraft's own frames still landed 3.8 to 4.6 ms apart after
	 * the hold (its own drawing time varies), so the shown speed still wobbled. Now the camera is
	 * placed on TF2's time-stamped path at the moment Minecraft sets up its camera: position =
	 * TF2's eye at (now - latency), interpolated between the two TF2 frames around that time. The
	 * motion then follows the clock, not the frame timing. -Dfortcraft.noSmoothCamera=true turns
	 * it off (camera at the newest overlay frame's eye, as in round 2).
	 */
	public static final boolean SMOOTH_CAMERA = !Boolean.getBoolean("fortcraft.noSmoothCamera");
	private static final int PATH = 32;
	private static final long[] pathUs = new long[PATH];
	private static final double[][] pathPos = new double[PATH][3];
	private static int pathCount, pathNext;
	private static long smoothFrames, extrapolatedFrames;

	private Overlay() {
	}

	/** True if this render frame got a new TF2 overlay frame (for the jitter log). */
	public static boolean newThisFrame() {
		return newThisFrame;
	}

	/** True while TF2 is linked and has sent at least one overlay frame. */
	public static boolean active() {
		return active;
	}

	private static boolean gpuPath() {
		return FortLink.gpuOverlayValid() && GpuOverlay.usable();
	}

	private static int currentSeq() {
		return gpuPath() ? FortLink.gpuOverlaySeq() : FortLink.overlaySeq();
	}

	/** Call once per render frame, before update: waits for TF2's next overlay frame (see PACING). */
	public static void pace(Minecraft minecraft, boolean guestAlive) {
		shownStampUs = 0;
		if (!PACING || !guestAlive || !active || intervalNanos <= 0 || intervalNanos > 10_000_000.0
			|| minecraft.options.enableVsync().get()) {
			return;  // not linked, overlay slower than 100 frames/s, or the screen paces Minecraft
		}
		long start = System.nanoTime();
		if (start - lastNewNanos > 100_000_000L) {
			return;  // TF2 isn't sending right now (loading, a menu): don't hold Minecraft up
		}
		int seen = gpuPath() ? lastGpuSeq : lastSeq;
		if (currentSeq() != seen) {
			// Already here (it arrived while Minecraft was busy): it's late, show it at once.
			noteArrival();
			markShown(start);
			pacedFrames++;
			return;
		}
		long deadline = start + Math.min(MAX_WAIT_NANOS, (long) (2.5 * intervalNanos));
		long now = start;
		while (currentSeq() == seen && now < deadline) {
			Thread.onSpinWait();
			now = System.nanoTime();
		}
		if (now >= deadline) {
			waitTimeouts++;
		} else {
			noteArrival();
			now = holdUntilDue(now);
			markShown(now);
		}
		waitNanos += now - start;
		pacedFrames++;
		if (lastPresentNanos != 0 && now - lastPresentNanos < 50_000_000L) {
			double gap = (now - lastPresentNanos) / 1e6;
			presentGapSum += gap;
			presentGapSqSum += gap * gap;
			presentGaps++;
		}
		lastPresentNanos = now;
	}

	/**
	 * A new TF2 frame has arrived: hold it until (TF2's start time for it + steady latency), so it
	 * goes on screen evenly spaced from the previous one. Returns the time Minecraft goes on.
	 */
	private static long holdUntilDue(long now) {
		int front = gpuPath() ? FortLink.gpuOverlayFront() : FortLink.overlayFront();
		long startedUs = FortLink.overlayTimeUs(front);
		if (startedUs == 0) {
			return now;
		}
		long started = startedUs * 1000L;
		long delay = now - started;
		if (delay < 0 || delay > 50_000_000L || latencyNanos <= 0) {
			return now;  // TF2's stamp isn't on our clock (old TF2 build?) or no latency yet: just show it
		}
		long due = started + latencyNanos;
		if (now >= due) {
			lateFrames++;
			return now;
		}
		while (now < due) {
			Thread.onSpinWait();
			now = System.nanoTime();
		}
		presentErrSum += (now - due) / 1e6;
		presentErrCount++;
		return now;
	}

	/** True while Minecraft is pacing its frames to TF2's (its own frame limiter should be off then). */
	public static boolean pacing(Minecraft minecraft) {
		return PACING && active && intervalNanos > 0 && intervalNanos <= 10_000_000.0
			&& !minecraft.options.enableVsync().get() && System.nanoTime() - lastNewNanos < 100_000_000L;
	}

	/** Call once per render frame, before drawing. */
	public static void update(Minecraft minecraft, boolean guestAlive) {
		active = guestAlive && texture != null;
		newThisFrame = false;
		if (!guestAlive) {
			return;
		}
		update2(minecraft);
		if (active && !newThisFrame) {
			repeatFrames++;
		}
	}

	private static void update2(Minecraft minecraft) {
		noteArrival();  // no-op if pace() already noted this frame
		if (FortLink.gpuOverlayValid() && GpuOverlay.usable()) {
			updateFromGpu(minecraft);
			return;
		}

		int seq = FortLink.overlaySeq();
		if (seq == lastSeq) {
			return;
		}
		countSkipped(seq, lastSeq);
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
		countSkipped(seq, lastGpuSeq);
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

	/** TF2 frames published since the last one shown, minus the one shown now (sequence numbers count by one). */
	private static void countSkipped(int seq, int previous) {
		int gap = seq - previous - 1;
		if (gap > 0 && gap < 1000 && lastNewNanos != 0) {
			skippedFrames += gap;
		}
	}

	private static void takePose(int slot) {
		float[] pose = FortLink.overlayPose(slot);
		shownSlot = slot;
		poseYaw = pose[0];
		posePitch = pose[1];
		// pose[5] is TF2's hasPosition flag (an int 1) already converted to the float 1.0. The old test
		// looked for the raw bits of 1 (0x00000001), never true, so the eye-position anchor (and round
		// 3's camera path) never ran: the camera used Minecraft's player position instead.
		havePosePosition = pose[5] == 1.0f;
		poseX = pose[2];
		poseY = pose[3];
		poseZ = pose[4];
		havePose = FortLink.overlayAgeMs(slot) >= 0;
		long stampUs = FortLink.overlayTimeUs(slot);
		if (havePosePosition && stampUs != 0) {
			int newest = (pathNext + PATH - 1) % PATH;
			if (pathCount > 0 && stampUs <= pathUs[newest]) {
				return;  // same or older frame
			}
			if (pathCount > 0 && Math.abs(poseX - pathPos[newest][0]) + Math.abs(poseY - pathPos[newest][1])
				+ Math.abs(poseZ - pathPos[newest][2]) > 4.0) {
				pathCount = 0;  // a teleport or respawn: start the path again
			}
			pathUs[pathNext] = stampUs;
			pathPos[pathNext][0] = poseX;
			pathPos[pathNext][1] = poseY;
			pathPos[pathNext][2] = poseZ;
			pathNext = (pathNext + 1) % PATH;
			pathCount = Math.min(PATH, pathCount + 1);
		}
	}

	/**
	 * Note how long after TF2 started the newest frame Minecraft first saw it, once per frame and
	 * before any hold of ours. (Round 3 measured it after the hold, so the hold fed back into the
	 * latency: it climbed to 9-12 ms, Minecraft dropped to 120 fps and TF2's stickies trailed
	 * Minecraft's world when strafing.)
	 */
	private static void noteArrival() {
		int seq = currentSeq();
		if (seq == delaySeq) {
			return;
		}
		delaySeq = seq;
		int front = gpuPath() ? FortLink.gpuOverlayFront() : FortLink.overlayFront();
		long stampUs = FortLink.overlayTimeUs(front);
		if (stampUs != 0) {
			noteDelay(System.nanoTime() - stampUs * 1000L);
		}
	}

	private static int delaySeq = Integer.MIN_VALUE;

	// The overlay frame this Minecraft frame shows: its TF2 start time, and when Minecraft let it
	// through (pace). 0 when not paced this frame.
	private static long shownStampUs, shownReleasedNanos;

	private static void markShown(long releasedNanos) {
		int front = gpuPath() ? FortLink.gpuOverlayFront() : FortLink.overlayFront();
		shownStampUs = FortLink.overlayTimeUs(front);
		shownReleasedNanos = releasedNanos;
	}

	/** How long after TF2 started a frame Minecraft got it; the latency covers the largest recent one. */
	private static void noteDelay(long delay) {
		if (delay < 0 || delay > 50_000_000L) {
			clockMismatch++;
			return;
		}
		delays[delayNext] = delay;
		delayNext = (delayNext + 1) % DELAY_WINDOW;
		delayCount = Math.min(DELAY_WINDOW, delayCount + 1);
		long max = 0;
		for (int i = 0; i < delayCount; i++) {
			max = Math.max(max, delays[i]);
		}
		latencyNanos = Math.min(max + 300_000L, intervalNanos > 0 ? Math.min(MAX_WAIT_NANOS, (long) intervalNanos * 2 + 300_000L) : MAX_WAIT_NANOS);
	}

	/**
	 * TF2's eye position at (now - latency), interpolated between the two TF2 frames around that
	 * time. Null if the path isn't known yet.
	 */
	private static double[] eyeOnPath() {
		if (!SMOOTH_CAMERA || pathCount < 2 || latencyNanos <= 0) {
			return null;
		}
		// The moment to show: the shown overlay frame's TF2 start time, moved on by however long
		// Minecraft has taken since letting it through, so TF2's objects in the overlay and
		// Minecraft's world match. Without pacing: now minus the latency.
		long nowNanos = System.nanoTime();
		long targetUs = shownStampUs != 0 && nowNanos - shownReleasedNanos < 20_000_000L
			? shownStampUs + (nowNanos - shownReleasedNanos) / 1000L
			: (nowNanos - latencyNanos) / 1000L;
		int newest = (pathNext + PATH - 1) % PATH;
		if (targetUs >= pathUs[newest]) {
			// Later than TF2's newest frame (a late frame): carry on along the last step, for at
			// most one more frame's worth, rather than stop.
			int prev = (newest + PATH - 1) % PATH;
			long span = pathUs[newest] - pathUs[prev];
			if (span <= 0 || span > 50_000) {
				return pathPos[newest].clone();
			}
			double f = Math.min(1.0, (targetUs - pathUs[newest]) / (double) span);
			extrapolatedFrames++;
			return lerp(pathPos[newest], pathPos[newest], pathPos[prev], -f);
		}
		for (int k = 1; k < pathCount; k++) {
			int b = (pathNext + PATH - k) % PATH;
			int a = (b + PATH - 1) % PATH;
			if (pathUs[a] <= targetUs) {
				long span = pathUs[b] - pathUs[a];
				double f = span > 0 ? (targetUs - pathUs[a]) / (double) span : 1.0;
				smoothFrames++;
				return lerp(pathPos[a], pathPos[a], pathPos[b], f);
			}
		}
		int oldest = (pathNext + PATH - pathCount) % PATH;
		return pathPos[oldest].clone();
	}

	/** base + (to - from) * f */
	private static double[] lerp(double[] base, double[] from, double[] to, double f) {
		return new double[] { base[0] + (to[0] - from[0]) * f, base[1] + (to[1] - from[1]) * f, base[2] + (to[2] - from[2]) * f };
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
		if (!(ANCHOR && active && havePose && havePosePosition)) {
			return null;
		}
		double[] smooth = eyeOnPath();
		lastCamera = smooth != null ? smooth : new double[] { poseX, poseY, poseZ };
		lastCameraNanos = System.nanoTime();
		return lastCamera;
	}

	private static double[] lastCamera;
	private static long lastCameraNanos;

	/** When the last frame placed its camera (System.nanoTime), for the jitter log. */
	public static long lastCameraNanos() {
		return lastCameraNanos;
	}

	/** The camera position the last frame used (for the jitter log), or null. */
	public static double[] lastCameraPosition() {
		return lastCamera;
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
		newThisFrame = true;
		long arrived = System.nanoTime();
		if (lastNewNanos != 0) {
			long gap = arrived - lastNewNanos;
			if (gap < 100_000_000L) {
				intervalNanos = intervalNanos <= 0 ? gap : intervalNanos * 0.95 + gap * 0.05;
			}
		}
		lastNewNanos = arrived;
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
			FortCraft.LOG.info("FortCraft: jitter {}x{}: pacing {} ({} paced frames/s, wait avg {} ms, {} gave up); "
				+ "frames without a new TF2 frame {}/s, TF2 frames never shown {}/s",
				w, h, PACING ? (minecraft.options.enableVsync().get() ? "off (vsync)" : "on") : "off (-Dfortcraft.noPacing)",
				String.format("%.0f", pacedFrames / seconds), String.format("%.2f", waitNanos / 1e6 / Math.max(1, pacedFrames)),
				waitTimeouts, String.format("%.0f", repeatFrames / seconds), String.format("%.0f", skippedFrames / seconds));
			double meanGap = presentGapSum / Math.max(1, presentGaps);
			double spread = Math.sqrt(Math.max(0, presentGapSqSum / Math.max(1, presentGaps) - meanGap * meanGap));
			FortCraft.LOG.info("FortCraft: jitter timing: frames shown {} ms apart (spread {} ms), latency {} ms, "
				+ "{} late, {} with an unusable TF2 time stamp; smooth camera {} ({} frames/s on TF2's path, {} carried past it)",
				String.format("%.2f", meanGap), String.format("%.2f", spread), String.format("%.2f", latencyNanos / 1e6),
				lateFrames, clockMismatch, SMOOTH_CAMERA ? "on" : "off", String.format("%.0f", smoothFrames / seconds),
				extrapolatedFrames);
			lateFrames = clockMismatch = presentGaps = smoothFrames = extrapolatedFrames = 0;
			presentGapSum = presentGapSqSum = 0;
			repeatFrames = skippedFrames = waitNanos = waitTimeouts = pacedFrames = 0;
			driftSum = driftMax = ageSum = 0;
			driftSamples = 0;
			logStart = now;
			framesAtLog = frames;
			copyNanos = 0;
		}
	}

	/**
	 * Draw it over the whole screen, smoothly scaled. Called from HudMixin in place of Minecraft's
	 * HUD. TF2 sends premultiplied alpha, so it is blended as such (no white edges or washed-out
	 * glows).
	 */
	public static void draw(GuiGraphicsExtractor graphics) {
		((dev.fortcraft.mixin.GuiGraphicsAccessor) graphics).fortcraft$innerBlit(
			net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
			texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
			0, 0, graphics.guiWidth(), graphics.guiHeight(), 0.0F, 1.0F, 0.0F, 1.0F, -1);
	}
}
