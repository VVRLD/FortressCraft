package dev.fortcraft.link;

import static java.lang.foreign.ValueLayout.*;

import dev.fortcraft.FortCraft;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * The Minecraft end of the shared-memory link. Minecraft is the host, so it creates the
 * mapping; TF2 opens it. Mirrors protocol/fortcraft_protocol.h: keep the two in step.
 */
public final class FortLink {
	public static final int MAGIC = 0x46524346;  // "FCRF"
	public static final int VERSION = 56;
	/** Windows: a named page-file mapping. Linux: a file in /dev/shm (RAM), which TF2 maps too. */
	public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	public static final String NAME = System.getenv().getOrDefault("FORTCRAFT_LINK", WINDOWS ? "Local\\FortCraft_v1" : "/dev/shm/FortCraft_v1");
	public static final int MAX_OVERLAY_W = 3840, MAX_OVERLAY_H = 2160;
	private static final long OVERLAY_SLOT_BYTES = (long) MAX_OVERLAY_W * MAX_OVERLAY_H * 4;
	private static final long O_PIXELS = 0x100000;
	private static final long SIZE = O_PIXELS + 2 * OVERLAY_SLOT_BYTES;

	// OverlayHeader @0x200: seq, front, width, height
	private static final long O = 0x200;
	private static final long HEARTBEAT_TIMEOUT_MS = 1000;

	// Header @0x0
	private static final long H_MAGIC = 0x00;
	private static final long H_VERSION = 0x04;
	private static final long H_HOST_PID = 0x08;
	private static final long H_GUEST_PID = 0x0C;
	private static final long H_HOST_HEARTBEAT = 0x10;
	private static final long H_GUEST_HEARTBEAT = 0x18;
	private static final long H_HOST_FRAME = 0x20;

	// PlayerState @0x100
	private static final long P = 0x100;
	private static final long P_SEQ = P;
	private static final long P_FLAGS = P + 0x04;
	private static final long P_SAMPLE = P + 0x08;
	private static final long P_X = P + 0x10;
	private static final long P_Y = P + 0x18;
	private static final long P_Z = P + 0x20;
	private static final long P_YAW = P + 0x28;
	private static final long P_PITCH = P + 0x2C;
	private static final long P_EYE = P + 0x30;
	private static final int PLAYER_VALID = 1;

	// InputState @0x140
	private static final long I = 0x140;
	private static final long I_SEQ = I;
	private static final long I_BUTTONS = I + 0x04;
	private static final long I_SAMPLE = I + 0x08;
	private static final long I_YAW = I + 0x10;
	private static final long I_PITCH = I + 0x14;
	private static final long I_WEAPON_SLOT = I + 0x18;
	public static final int IN_FORWARD = 1, IN_BACK = 2, IN_LEFT = 4, IN_RIGHT = 8, IN_JUMP = 16, IN_CROUCH = 32;
	public static final int IN_ATTACK = 64, IN_RELOAD = 128, IN_ATTACK2 = 256;

	// BlockBoxes @0x1000: seq, count, then boxes of 6 floats (min xyz, max xyz) in block coords
	private static final long B = 0x1000;
	private static final long B_SEQ = B;
	private static final long B_COUNT = B + 0x04;
	private static final long B_BOXES = B + 0x08;
	public static final int MAX_BOXES = 8192;

	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;

	private static final VarHandle INT = JAVA_INT.varHandle();
	private static final VarHandle LONG = JAVA_LONG.varHandle();

	private static final MethodHandle CREATE_FILE_MAPPING;
	private static final MethodHandle MAP_VIEW_OF_FILE;
	private static final MethodHandle GET_TICK_COUNT64;

	static {
		if (WINDOWS) {
			Linker linker = Linker.nativeLinker();
			SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
			CREATE_FILE_MAPPING = linker.downcallHandle(
				k32.find("CreateFileMappingW").orElseThrow(),
				FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)
			);
			MAP_VIEW_OF_FILE = linker.downcallHandle(
				k32.find("MapViewOfFile").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)
			);
			GET_TICK_COUNT64 = linker.downcallHandle(k32.find("GetTickCount64").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
		} else {
			CREATE_FILE_MAPPING = null;
			MAP_VIEW_OF_FILE = null;
			GET_TICK_COUNT64 = null;
		}
	}

	/** One reading of TF2's player state. */
	public record PlayerState(long sample, double x, double y, double z, float yaw, float pitch, float eyeHeight) {
	}

	private static MemorySegment shm;

	private FortLink() {
	}

	/** Create the mapping and write our half of the header. Call once at startup. */
	public static void create() {
		if (!WINDOWS) {
			createLinux();
			return;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(NAME, StandardCharsets.UTF_16LE);
			MemorySegment invalidHandle = MemorySegment.ofAddress(-1L);  // INVALID_HANDLE_VALUE: backed by the page file
			MemorySegment handle = (MemorySegment) CREATE_FILE_MAPPING.invokeExact(
				invalidHandle, MemorySegment.NULL, PAGE_READWRITE, 0, (int) SIZE, name
			);
			if (handle.address() == 0) {
				FortCraft.LOG.error("FortCraft: couldn't create shared memory {}", NAME);
				return;
			}
			MemorySegment view = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(handle, FILE_MAP_ALL_ACCESS, 0, 0, 0L);
			if (view.address() == 0) {
				FortCraft.LOG.error("FortCraft: couldn't map shared memory {}", NAME);
				return;
			}
			shm = view.reinterpret(SIZE);
		} catch (Throwable t) {
			FortCraft.LOG.error("FortCraft: shared memory setup failed", t);
			return;
		}
		writeHostHeader();
	}

	/**
	 * Linux: the link is a file in /dev/shm, which lives in RAM. A file left from an earlier run
	 * keeps its old bytes (a fresh Windows mapping starts as zeros), so the message area is
	 * cleared; overlay pixels are always rewritten before they are used.
	 */
	private static void createLinux() {
		try (FileChannel ch = FileChannel.open(Path.of(NAME), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			if (ch.size() < SIZE) {
				ch.write(ByteBuffer.wrap(new byte[1]), SIZE - 1);  // grow the file to the full size
			}
			shm = ch.map(FileChannel.MapMode.READ_WRITE, 0, SIZE, Arena.global());
		} catch (Throwable t) {
			FortCraft.LOG.error("FortCraft: shared memory setup failed ({})", NAME, t);
			shm = null;
			return;
		}
		shm.asSlice(0, O_PIXELS).fill((byte) 0);
		writeHostHeader();
	}

	private static void writeHostHeader() {
		shm.asSlice(0, 0x400).fill((byte) 0);
		shm.asSlice(0x58100, 4 + 32 * 4).fill((byte) 0);
		shm.set(JAVA_INT, H_VERSION, VERSION);
		shm.set(JAVA_INT, H_HOST_PID, (int) ProcessHandle.current().pid());
		INT.setRelease(shm, H_MAGIC, MAGIC);  // last, so TF2 never sees a half-written header
		FortCraft.LOG.info("FortCraft: link created {} version {}", NAME, VERSION);
	}

	/** Call once per Minecraft frame. */
	public static void heartbeat(long frame) {
		if (shm == null) {
			return;
		}
		shm.set(JAVA_LONG, H_HOST_FRAME, frame);
		LONG.setRelease(shm, H_HOST_HEARTBEAT, tickCount());
	}

	/** Forward what the player is pressing and where they're looking. Call once per frame. */
	public static void writeInput(long sample, int buttons, float yaw, float pitch, int weaponSlot) {
		if (shm == null) {
			return;
		}
		int seq = shm.get(JAVA_INT, I_SEQ);
		INT.setRelease(shm, I_SEQ, (seq + 1) | 1);  // odd: writing
		shm.set(JAVA_INT, I_BUTTONS, buttons);
		shm.set(JAVA_LONG, I_SAMPLE, sample);
		shm.set(JAVA_FLOAT, I_YAW, yaw);
		shm.set(JAVA_FLOAT, I_PITCH, pitch);
		shm.set(JAVA_INT, I_WEAPON_SLOT, weaponSlot);
		INT.setRelease(shm, I_SEQ, ((seq + 1) | 1) + 1);  // even: done
	}

	/** Replace the block box list. boxes holds 6 floats per box; count is the number of boxes. */
	public static void writeBoxes(float[] boxes, int count) {
		if (shm == null) {
			return;
		}
		count = Math.min(count, MAX_BOXES);
		int seq = shm.get(JAVA_INT, B_SEQ);
		INT.setRelease(shm, B_SEQ, (seq + 1) | 1);  // odd: writing
		MemorySegment.copy(boxes, 0, shm, JAVA_FLOAT, B_BOXES, count * 6);
		shm.set(JAVA_INT, B_COUNT, count);
		INT.setRelease(shm, B_SEQ, ((seq + 1) | 1) + 1);  // even: done
	}

	// WaterBoxes @0x56000: same seqlocked box layout as BlockBoxes, but no collision.
	public static final int MAX_WATER_BOXES = 256;

	public static void writeWaterBoxes(float[] boxes, int count) {
		if (shm == null) {
			return;
		}
		count = Math.min(count, MAX_WATER_BOXES);
		int seq = shm.get(JAVA_INT, 0x56000);
		INT.setRelease(shm, 0x56000, (seq + 1) | 1);
		MemorySegment.copy(boxes, 0, shm, JAVA_FLOAT, 0x56008, count * 6);
		shm.set(JAVA_INT, 0x56004, count);
		INT.setRelease(shm, 0x56000, ((seq + 1) | 1) + 1);
	}

	// VisualBoxes @0x32000 (Minecraft -> TF2): seq, count, boxes of 6 floats. Walk-through blocks
	// (grass, flowers...) for TF2's depth buffer only.
	public static final int MAX_VISUAL_BOXES = 2300;

	public static void writeVisualBoxes(float[] boxes, int count) {
		if (shm == null) {
			return;
		}
		count = Math.min(count, MAX_VISUAL_BOXES);
		int seq = shm.get(JAVA_INT, 0x32000);
		INT.setRelease(shm, 0x32000, (seq + 1) | 1);
		MemorySegment.copy(boxes, 0, shm, JAVA_FLOAT, 0x32008, count * 6);
		shm.set(JAVA_INT, 0x32004, count);
		INT.setRelease(shm, 0x32000, ((seq + 1) | 1) + 1);
	}

	/** Minecraft's framebuffer size and frame rate limit (HostDisplay @0x1A0), so TF2 can match them. */
	public static void writeDisplay(int width, int height, int fpsLimit, boolean cursorFree, boolean creative, boolean noGpuOverlay, float fovY, float aspect, float light) {
		if (shm == null) {
			return;
		}
		shm.set(JAVA_INT, 0x1A0, width);
		shm.set(JAVA_INT, 0x1A4, height);
		shm.set(JAVA_INT, 0x1A8, fpsLimit);
		shm.set(JAVA_INT, 0x1AC, (cursorFree ? 1 : 0) | (creative ? 2 : 0) | (noGpuOverlay ? 4 : 0));
		shm.set(JAVA_FLOAT, 0x1B0, fovY);
		shm.set(JAVA_FLOAT, 0x1B4, aspect);
		shm.set(JAVA_FLOAT, 0x1B8, light);
	}

	/** One projectile in flight: TF2 entity index, kind (0 other, 1 rocket, 2 grenade), Minecraft position. */
	public record Projectile(int id, int kind, float x, float y, float z) {
	}

	/** TF2's projectiles in flight (Projectiles @0x400), or an empty list if TF2 was mid-write. */
	public static java.util.List<Projectile> readProjectiles() {
		if (shm == null) {
			return java.util.List.of();
		}
		int before = (int) INT.getAcquire(shm, 0x400);
		if ((before & 1) != 0) {
			return java.util.List.of();
		}
		int count = Math.min(shm.get(JAVA_INT, 0x404), 96);
		java.util.List<Projectile> list = new java.util.ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			long o = 0x408 + i * 20L;
			list.add(new Projectile(shm.get(JAVA_INT, o), shm.get(JAVA_INT, o + 4),
				shm.get(JAVA_FLOAT_UNALIGNED, o + 8), shm.get(JAVA_FLOAT_UNALIGNED, o + 12), shm.get(JAVA_FLOAT_UNALIGNED, o + 16)));
		}
		VarHandle.acquireFence();
		return (int) INT.getAcquire(shm, 0x400) == before ? list : java.util.List.of();
	}

	// MobHits @0x53800 (Minecraft -> TF2): count, pad, then a ring of 32 x (x, y, z, damage, killed, pad).
	public static synchronized void writeMobHit(double x, double y, double z, float tf2Damage, boolean killed) {
		writeMobHit(x, y, z, tf2Damage, killed, 0);
	}

	/** source: the TF2 shot flags behind the hit (CombatRules.SENTRY etc.), for TF2's own bookkeeping. */
	public static synchronized void writeMobHit(double x, double y, double z, float tf2Damage, boolean killed, int source) {
		if (shm == null) {
			return;
		}
		int count = shm.get(JAVA_INT, 0x53800);
		long o = 0x53808 + Integer.remainderUnsigned(count, 32) * 24L;
		shm.set(JAVA_FLOAT, o, (float) x);
		shm.set(JAVA_FLOAT, o + 4, (float) y);
		shm.set(JAVA_FLOAT, o + 8, (float) z);
		shm.set(JAVA_FLOAT, o + 12, tf2Damage);
		shm.set(JAVA_INT, o + 16, killed ? 1 : 0);
		shm.set(JAVA_INT, o + 20, source);
		INT.setRelease(shm, 0x53800, count + 1);
	}

	/** One Engineer building: TF2 entity index and box, Minecraft coordinates. */
	public record Tf2Building(int ent, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, boolean sentry) {
	}

	// Buildings @0x54000 (TF2 -> Minecraft): seq, count, then up to 16 x (ent, pad, 6 floats). Null if mid-write.
	public static java.util.List<Tf2Building> readBuildings() {
		if (shm == null) {
			return java.util.List.of();
		}
		int before = (int) INT.getAcquire(shm, 0x54000);
		if ((before & 1) != 0) {
			return null;
		}
		int count = Math.min(shm.get(JAVA_INT, 0x54004), 16);
		var list = new java.util.ArrayList<Tf2Building>(count);
		for (int i = 0; i < count; i++) {
			long o = 0x54008 + i * 32L;
			list.add(new Tf2Building(shm.get(JAVA_INT, o), shm.get(JAVA_FLOAT, o + 8), shm.get(JAVA_FLOAT, o + 12),
				shm.get(JAVA_FLOAT, o + 16), shm.get(JAVA_FLOAT, o + 20), shm.get(JAVA_FLOAT, o + 24), shm.get(JAVA_FLOAT, o + 28),
				shm.get(JAVA_INT, o + 4) == 1));
		}
		VarHandle.acquireFence();
		return (int) INT.getAcquire(shm, 0x54000) == before ? list : null;
	}

	// BuildingHits @0x54400 (Minecraft -> TF2): count, pad, then a ring of 32 x (ent, amount).
	public static synchronized void writeBuildingHit(int ent, float minecraftDamage) {
		if (shm == null) {
			return;
		}
		int count = shm.get(JAVA_INT, 0x54400);
		long o = 0x54408 + Integer.remainderUnsigned(count, 32) * 8L;
		shm.set(JAVA_INT, o, ent);
		shm.set(JAVA_FLOAT, o + 4, minecraftDamage);
		INT.setRelease(shm, 0x54400, count + 1);
	}

	/** Total fire points TF2 has reported (FirePoints @0x53200). */
	public static int fireCount() {
		return shm == null ? 0 : (int) INT.getAcquire(shm, 0x53200);
	}

	/** Fire point number n (only the last 64 are kept): x, y, z, radius in blocks. */
	public static float[] fire(int n) {
		long o = 0x53208 + Integer.remainderUnsigned(n, 64) * 16L;
		return new float[] { shm.get(JAVA_FLOAT, o), shm.get(JAVA_FLOAT, o + 4), shm.get(JAVA_FLOAT, o + 8), shm.get(JAVA_FLOAT, o + 12) };
	}

	/** Total explosions TF2 has reported (Explosions @0xC00). */
	public static int explosionCount() {
		return shm == null ? 0 : (int) INT.getAcquire(shm, 0xC00);
	}

	/** Explosion number n (only the last 32 are kept): x, y, z, radius in blocks. */
	public static float[] explosion(int n) {
		long o = 0xC08 + (Integer.remainderUnsigned(n, 32)) * 24L;
		return new float[] { shm.get(JAVA_FLOAT, o), shm.get(JAVA_FLOAT, o + 4), shm.get(JAVA_FLOAT, o + 8), shm.get(JAVA_FLOAT, o + 12),
			shm.get(JAVA_FLOAT, o + 16), shm.get(JAVA_INT, o + 20) };
	}

	// OverlayGpu @0x300: seq, front, width, height, valid, generation, handle[2]
	public static boolean gpuOverlayValid() {
		return shm != null && (int) INT.getAcquire(shm, 0x310) == 1;
	}

	public static int gpuOverlaySeq() {
		return (int) INT.getAcquire(shm, 0x300);
	}

	public static int gpuOverlayFront() {
		return shm.get(JAVA_INT, 0x304) & 1;
	}

	public static int gpuOverlayWidth() {
		return shm.get(JAVA_INT, 0x308);
	}

	public static int gpuOverlayHeight() {
		return shm.get(JAVA_INT, 0x30C);
	}

	public static int gpuOverlayGeneration() {
		return (int) INT.getAcquire(shm, 0x314);
	}

	public static long gpuOverlayHandle(int i) {
		return shm.get(JAVA_LONG, 0x318 + 8L * i);
	}

	// Shots @0x40000: 64 x 48-byte shot records (protocol 38).
	public static int shotCount() {
		return shm == null ? 0 : (int) INT.getAcquire(shm, 0x40000);
	}

	public record Shot(float x, float y, float z, float dx, float dy, float dz, float range, float damage,
		int flags, int weapon, int tool, float headshotRange) { }

	public static Shot shot(int n) {
		long o = 0x40008 + Integer.remainderUnsigned(n, 64) * 48L;
		return new Shot(shm.get(JAVA_FLOAT, o), shm.get(JAVA_FLOAT, o + 4), shm.get(JAVA_FLOAT, o + 8),
			shm.get(JAVA_FLOAT, o + 12), shm.get(JAVA_FLOAT, o + 16), shm.get(JAVA_FLOAT, o + 20),
			shm.get(JAVA_FLOAT, o + 24), shm.get(JAVA_FLOAT, o + 28), shm.get(JAVA_INT, o + 32),
			shm.get(JAVA_INT, o + 36), shm.get(JAVA_INT, o + 40), shm.get(JAVA_FLOAT, o + 44));
	}

	/** TF2's render camera this frame (Camera @0x1D0), Minecraft coordinates and degrees. */
	public record Tf2Camera(boolean thirdPerson, boolean tauntMenu, boolean taunting, boolean uiOpen, boolean medigun, double x, double y, double z, float yaw, float pitch, float zoom, boolean cloaked, boolean eurekaMenu, boolean console) {
	}

	public static Tf2Camera readCamera() {
		if (shm == null) {
			return null;
		}
		int before = (int) INT.getAcquire(shm, 0x1D0);
		if ((before & 1) != 0) {
			return null;
		}
		int flags = shm.get(JAVA_INT, 0x1D4);
		Tf2Camera c = new Tf2Camera((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0,
			shm.get(JAVA_FLOAT, 0x1D8), shm.get(JAVA_FLOAT, 0x1DC), shm.get(JAVA_FLOAT, 0x1E0),
			shm.get(JAVA_FLOAT, 0x1E4), shm.get(JAVA_FLOAT, 0x1E8), shm.get(JAVA_FLOAT, 0x1EC), (flags & 32) != 0, (flags & 64) != 0, (flags & 128) != 0);
		VarHandle.acquireFence();
		return (int) INT.getAcquire(shm, 0x1D0) == before ? c : null;
	}

	// UiEvents @0x43000 (Minecraft -> TF2): count, pad, then a ring of 256 events
	// (type, code, x, y); see the protocol's UiEventType and UiCommands.
	public static final int UI_COMMAND = 1, UI_MOUSE_MOVE = 2, UI_MOUSE_DOWN = 3, UI_MOUSE_UP = 4, UI_WHEEL = 5,
		UI_KEY_DOWN = 6, UI_KEY_UP = 7, UI_CHAR = 8;

	/** Pass a key on to TF2's menus; see the protocol's UiCommands. */
	public static void sendUiCommand(int command) {
		sendUiEvent(UI_COMMAND, command, 0, 0);
	}

	/** One UI event for TF2 (mouse, key or command). */
	public static synchronized void sendUiEvent(int type, int code, float x, float y) {
		if (shm == null) {
			return;
		}
		int count = shm.get(JAVA_INT, 0x43000);
		long o = 0x43008 + (Integer.remainderUnsigned(count, 256)) * 16L;
		shm.set(JAVA_INT, o, type);
		shm.set(JAVA_INT, o + 4, code);
		shm.set(JAVA_FLOAT, o + 8, x);
		shm.set(JAVA_FLOAT, o + 12, y);
		INT.setRelease(shm, 0x43000, count + 1);
	}

	/** SpawnPoint @0x1C0: where TF2 should (re)spawn the player, Minecraft feet coordinates. */
	public static void writeSpawnPoint(float x, float y, float z) {
		if (shm == null) {
			return;
		}
		shm.set(JAVA_FLOAT, 0x1C4, x);
		shm.set(JAVA_FLOAT, 0x1C8, y);
		shm.set(JAVA_FLOAT, 0x1CC, z);
		INT.setRelease(shm, 0x1C0, 1);
	}

	// McTeleport @0x54900 (Minecraft -> TF2): seq, then feet x, y, z. Minecraft moved its player
	// itself (portal, ender pearl, /tp, respawn in another dimension); TF2 puts its player there.
	private static int mcTeleportSeq;

	public static void writeMcTeleport(float x, float y, float z) {
		if (shm == null) {
			return;
		}
		shm.set(JAVA_FLOAT, 0x54904, x);
		shm.set(JAVA_FLOAT, 0x54908, y);
		shm.set(JAVA_FLOAT, 0x5490C, z);
		INT.setRelease(shm, 0x54900, ++mcTeleportSeq);
	}

	// MobBoxes @0x42000 (Minecraft -> TF2): seq, count, then up to 128 boxes of 6 floats.
	public static void writeMobBoxes(float[] boxes, byte[] hostile, int count) {
		if (shm == null) {
			return;
		}
		count = Math.min(count, 128);
		int seq = shm.get(JAVA_INT, 0x42000);
		INT.setRelease(shm, 0x42000, (seq + 1) | 1);  // odd: writing
		MemorySegment.copy(boxes, 0, shm, JAVA_FLOAT, 0x42008, count * 6);
		MemorySegment.copy(hostile, 0, shm, JAVA_BYTE, 0x42E00, count);  // MobHostile
		shm.set(JAVA_INT, 0x42004, count);
		INT.setRelease(shm, 0x42000, ((seq + 1) | 1) + 1);  // even: done
	}

	/** One friendly Minecraft mob for TF2's invisible Medigun presentation proxy. */
	public static void writeMedicTarget(int entityId, float x, float y, float z,
		float health, float maxHealth, String name) {
		if (shm == null) {
			return;
		}
		int seq = shm.get(JAVA_INT, 0x55000);
		INT.setRelease(shm, 0x55000, (seq + 1) | 1);
		shm.set(JAVA_INT, 0x55004, entityId);
		shm.set(JAVA_FLOAT, 0x55008, x);
		shm.set(JAVA_FLOAT, 0x5500C, y);
		shm.set(JAVA_FLOAT, 0x55010, z);
		shm.set(JAVA_FLOAT, 0x55014, health * 5.0f);
		shm.set(JAVA_FLOAT, 0x55018, maxHealth * 5.0f);
		putString(0x5501C, name == null ? "" : name);
		INT.setRelease(shm, 0x55000, ((seq + 1) | 1) + 1);
	}

	/** One Minecraft item type for TF2's backpack (BackpackStack). */
	public record BackpackStack(String id, String name, String icon, int count) {
	}

	// Backpack @0x45000 (Minecraft -> TF2): seq, count, then up to 50 x (id[64], name[64], icon[64], count, pad).
	public static void writeBackpack(java.util.List<BackpackStack> stacks) {
		if (shm == null) {
			return;
		}
		int count = Math.min(stacks.size(), 50);
		int seq = shm.get(JAVA_INT, 0x45000);
		INT.setRelease(shm, 0x45000, (seq + 1) | 1);  // odd: writing
		for (int i = 0; i < count; i++) {
			BackpackStack s = stacks.get(i);
			long o = 0x45008 + i * 200L;
			putString(o, s.id());
			putString(o + 64, s.name());
			putString(o + 128, s.icon());
			shm.set(JAVA_INT, o + 192, s.count());
			shm.set(JAVA_INT, o + 196, 0);
		}
		shm.set(JAVA_INT, 0x45004, count);
		INT.setRelease(shm, 0x45000, ((seq + 1) | 1) + 1);  // even: done
	}

	// HandRequest @0x48000 (TF2 -> Minecraft): count, pad, id[64] ("" = put away).
	public static int handRequestCount() {
		return shm == null ? 0 : (int) INT.getAcquire(shm, 0x48000);
	}

	public static String handRequestId() {
		return shm == null ? "" : getString(0x48008);
	}

	// HandState @0x48100 (Minecraft -> TF2): equipped, pad, id[64].
	public static void writeHandState(String id) {
		if (shm == null) {
			return;
		}
		INT.setRelease(shm, 0x48100, 0);
		putString(0x48108, id == null ? "" : id);
		INT.setRelease(shm, 0x48100, id == null ? 0 : 1);
	}

	private static String getString(long offset) {
		byte[] bytes = new byte[64];
		MemorySegment.copy(shm, JAVA_BYTE, offset, bytes, 0, 64);
		int n = 0;
		while (n < 64 && bytes[n] != 0) {
			n++;
		}
		return new String(bytes, 0, n, StandardCharsets.UTF_8);
	}

	/** One craftable Minecraft recipe for TF2's crafting screen (CraftRecipe). */
	public record CraftRecipe(String key, String name, String inputs, String resultId, String resultIcon, java.util.List<String> ingredients) {
	}

	// CraftRecipes @0x48200 (Minecraft -> TF2): seq, count, then up to 64 x
	// (key[64], name[64], inputs[128], resultId[64], resultIcon[64], ingredients[4][64]).
	public static void writeRecipes(java.util.List<CraftRecipe> recipes) {
		if (shm == null) {
			return;
		}
		int count = Math.min(recipes.size(), 64);
		int seq = shm.get(JAVA_INT, 0x48200);
		INT.setRelease(shm, 0x48200, (seq + 1) | 1);  // odd: writing
		for (int i = 0; i < count; i++) {
			CraftRecipe r = recipes.get(i);
			long o = 0x48208 + i * 640L;
			putString(o, r.key());
			putString(o + 64, r.name());
			putString(o + 128, r.inputs(), 128);
			putString(o + 256, r.resultId());
			putString(o + 320, r.resultIcon());
			for (int k = 0; k < 4; k++) {
				putString(o + 384 + k * 64L, k < r.ingredients().size() ? r.ingredients().get(k) : "");
			}
		}
		shm.set(JAVA_INT, 0x48204, count);
		INT.setRelease(shm, 0x48200, ((seq + 1) | 1) + 1);  // even: done
	}

	// CraftRequest @0x53000 (TF2 -> Minecraft): count, pad, key[64].
	public static int craftRequestCount() {
		return shm == null ? 0 : (int) INT.getAcquire(shm, 0x53000);
	}

	public static String craftRequestKey() {
		return shm == null ? "" : getString(0x53008);
	}

	// CraftResult @0x53100 (Minecraft -> TF2): count, ok. ok first, then count.
	public static synchronized void writeCraftResult(boolean ok) {
		if (shm == null) {
			return;
		}
		shm.set(JAVA_INT, 0x53104, ok ? 1 : 0);
		INT.setRelease(shm, 0x53100, shm.get(JAVA_INT, 0x53100) + 1);
	}

	// PackUse @0x58000: request, kind, result, ok. One outstanding use at a time.
	public static int requestPackUse(int kind) {
		if (shm == null) return 0;
		shm.set(JAVA_INT, 0x58004, kind);
		int request = shm.get(JAVA_INT, 0x58000) + 1;
		INT.setRelease(shm, 0x58000, request);
		return request;
	}

	// FarObjects @0x5B300 (v56, TF2 writes): seq, count, then up to 512 {ent, x, y, z, radius}.
	// FarHidden @0x5DC00 (we write): seq, count, entity indices we can't see.
	public record FarObject(int ent, double x, double y, double z, double radius) {
	}

	/** TF2's far objects, or null mid-write. */
	public static FarObject[] readFarObjects() {
		if (shm == null) {
			return null;
		}
		int seq = (int) INT.getAcquire(shm, 0x5B300);
		if ((seq & 1) != 0) {
			return null;
		}
		int n = Math.min(Math.max(0, shm.get(JAVA_INT, 0x5B304)), 512);
		FarObject[] out = new FarObject[n];
		for (int i = 0; i < n; i++) {
			long o = 0x5B308 + i * 20L;
			out[i] = new FarObject(shm.get(JAVA_INT, o), shm.get(JAVA_FLOAT, o + 4), shm.get(JAVA_FLOAT, o + 8),
				shm.get(JAVA_FLOAT, o + 12), shm.get(JAVA_FLOAT, o + 16));
		}
		VarHandle.acquireFence();
		return (int) INT.getAcquire(shm, 0x5B300) == seq ? out : null;
	}

	public static void writeFarHidden(int[] ents, int count) {
		if (shm == null) {
			return;
		}
		count = Math.min(count, 512);
		int seq = shm.get(JAVA_INT, 0x5DC00);
		INT.setRelease(shm, 0x5DC00, (seq + 1) | 1);  // odd: writing
		shm.set(JAVA_INT, 0x5DC04, count);
		for (int i = 0; i < count; i++) {
			shm.set(JAVA_INT, 0x5DC08 + i * 4L, ents[i]);
		}
		INT.setRelease(shm, 0x5DC00, ((seq + 1) | 1) + 1);  // even: done
	}

	/** One saved MvM upgrade: TF2 class, item definition (65535 = the player), upgrade index, cost. */
	public record MvmUpgrade(int playerClass, int itemDef, int upgrade, int cost) {
	}

	/** MvmState @0x59100 (v55, TF2 -> Minecraft): money and upgrades now, and the restore applied. */
	public record MvmState(int restoreApplied, int currency, java.util.List<MvmUpgrade> upgrades) {
	}

	public static MvmState readMvmState() {
		if (shm == null) {
			return null;
		}
		int seq = (int) INT.getAcquire(shm, 0x59100);
		if ((seq & 1) != 0 || seq == 0) {
			return null;
		}
		int applied = shm.get(JAVA_INT, 0x59104);
		int currency = shm.get(JAVA_INT, 0x59108);
		int count = Math.min(Math.max(0, shm.get(JAVA_INT, 0x5910C)), 256);
		var list = new java.util.ArrayList<MvmUpgrade>(count);
		for (int i = 0; i < count; i++) {
			long o = 0x59110 + i * 16L;
			list.add(new MvmUpgrade(shm.get(JAVA_INT, o), shm.get(JAVA_INT, o + 4), shm.get(JAVA_INT, o + 8), shm.get(JAVA_INT, o + 12)));
		}
		VarHandle.acquireFence();
		return (int) INT.getAcquire(shm, 0x59100) == seq ? new MvmState(applied, currency, list) : null;
	}

	/** MvmRestore @0x5A200 (v55, Minecraft -> TF2): the world's saved money and upgrades. Returns its seq. */
	public static int writeMvmRestore(int currency, java.util.List<MvmUpgrade> upgrades) {
		if (shm == null) {
			return 0;
		}
		int count = Math.min(upgrades.size(), 256);
		shm.set(JAVA_INT, 0x5A204, currency);
		shm.set(JAVA_INT, 0x5A208, count);
		for (int i = 0; i < count; i++) {
			long o = 0x5A210 + i * 16L;
			MvmUpgrade u = upgrades.get(i);
			shm.set(JAVA_INT, o, u.playerClass());
			shm.set(JAVA_INT, o + 4, u.itemDef());
			shm.set(JAVA_INT, o + 8, u.upgrade());
			shm.set(JAVA_INT, o + 12, u.cost());
		}
		int seq = shm.get(JAVA_INT, 0x5A200) + 1;
		if (seq == 0) {
			seq = 1;
		}
		INT.setRelease(shm, 0x5A200, seq);
		return seq;
	}

	/** UpgradeStation @0x59000 (v50): the player used an enchanting table (TF2's MvM upgrades). */
	public static void writeUpgradeStation(int bookshelves, double x, double y, double z) {
		if (shm == null) {
			return;
		}
		shm.set(JAVA_INT, 0x59004, bookshelves);
		shm.set(JAVA_FLOAT, 0x59008, (float) x);
		shm.set(JAVA_FLOAT, 0x5900C, (float) y);
		shm.set(JAVA_FLOAT, 0x59010, (float) z);
		INT.setRelease(shm, 0x59000, shm.get(JAVA_INT, 0x59000) + 1);
	}

	/** MobBackstab @0x58F00 (v46): 1 while a mob's back is in knife reach of TF2's aim. */
	public static void writeMobBackstab(boolean ready) {
		if (shm != null) {
			shm.set(JAVA_INT, 0x58F00, ready ? 1 : 0);
		}
	}

	/** WaterLines @0x58C00 (v45): seq, count, then {ent, Minecraft y of the water surface}. */
	public static void writeWaterLines(int[] ents, float[] surfaceY, int count) {
		if (shm == null) {
			return;
		}
		count = Math.min(count, 64);
		int seq = shm.get(JAVA_INT, 0x58C00);
		INT.setRelease(shm, 0x58C00, (seq + 1) | 1);  // odd: writing
		shm.set(JAVA_INT, 0x58C04, count);
		for (int i = 0; i < count; i++) {
			shm.set(JAVA_INT, 0x58C08 + i * 8L, ents[i]);
			shm.set(JAVA_FLOAT, 0x58C0C + i * 8L, surfaceY[i]);
		}
		INT.setRelease(shm, 0x58C00, ((seq + 1) | 1) + 1);  // even: done
	}

	/** Ground @0x58B00: Minecraft's friction for the block under the player (0.6 normal, 0.98 ice). */
	public static void writeGroundFriction(float friction) {
		if (shm != null) {
			shm.set(JAVA_FLOAT, 0x58B00, friction);
		}
	}

	public static int packUseResult() {
		return shm == null ? 0 : (int) INT.getAcquire(shm, 0x58008);
	}

	public static boolean packUseOk() {
		return shm != null && shm.get(JAVA_INT, 0x5800C) != 0;
	}

	// FoodHeals @0x58100: count, then 32 nutrition values. Written only after eating finishes.
	public static synchronized void writeFoodHeal(int nutrition) {
		if (shm == null || nutrition <= 0) return;
		int count = shm.get(JAVA_INT, 0x58100);
		shm.set(JAVA_INT, 0x58104 + Integer.remainderUnsigned(count, 32) * 4L, nutrition);
		INT.setRelease(shm, 0x58100, count + 1);
	}

	private static void putString(long offset, String s) {
		putString(offset, s, 64);
	}

	/** A NUL-terminated UTF-8 string in a field of this size, cut at a whole character if too long. */
	private static void putString(long offset, String s, int size) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		int n = Math.min(bytes.length, size - 1);
		while (n > 0 && n < bytes.length && (bytes[n] & 0xC0) == 0x80) {
			n--; // don't split a multi-byte character
		}
		shm.asSlice(offset, size).fill((byte) 0);
		MemorySegment.copy(bytes, 0, shm, JAVA_BYTE, offset, n);
	}

	// Hurts @0x41000 (Minecraft -> TF2): count, then a ring of 32 x (amount, fromX, fromY, fromZ).
	public static synchronized void writeHurt(float tf2Damage, double fromX, double fromY, double fromZ) {
		if (shm == null) {
			return;
		}
		int count = shm.get(JAVA_INT, 0x41000);
		long o = 0x41008 + Integer.remainderUnsigned(count, 32) * 16L;
		shm.set(JAVA_FLOAT, o, tf2Damage);
		shm.set(JAVA_FLOAT, o + 4, (float) fromX);
		shm.set(JAVA_FLOAT, o + 8, (float) fromY);
		shm.set(JAVA_FLOAT, o + 12, (float) fromZ);
		INT.setRelease(shm, 0x41000, count + 1);
	}

	/** OverlayPose @0x240 for slot (CPU) or texture (GPU) 0/1: {yaw, pitch, x, y, z, hasPosition}. */
	public static float[] overlayPose(int slot) {
		long o = 0x240 + (slot & 1) * 32L;
		return new float[] { shm.get(JAVA_FLOAT, o), shm.get(JAVA_FLOAT, o + 4), shm.get(JAVA_FLOAT, o + 16),
			shm.get(JAVA_FLOAT, o + 20), shm.get(JAVA_FLOAT, o + 24), shm.get(JAVA_INT, o + 28) };
	}

	/** How old the overlay in slot 0/1 is, in ms (TF2 started rendering it then). */
	public static long overlayAgeMs(int slot) {
		long t = overlayTimeUs(slot);
		return t == 0 ? -1 : (System.nanoTime() / 1000L - t) / 1000L;
	}

	/**
	 * When TF2 started rendering the overlay in slot 0/1, in microseconds on System.nanoTime's
	 * clock (TF2 reads the same clock: QueryPerformanceCounter on Windows, CLOCK_MONOTONIC on
	 * Linux). 0 if not known.
	 */
	public static long overlayTimeUs(int slot) {
		return shm.get(JAVA_LONG, 0x240 + (slot & 1) * 32L + 8);
	}

	/** The CPU path's newest slot (0 or 1). */
	public static int overlayFront() {
		return (int) INT.getAcquire(shm, O + 0x04) & 1;
	}

	/** Overlay frame counter; changes when TF2 has written a new frame. */
	public static int overlaySeq() {
		return shm == null ? 0 : (int) INT.getAcquire(shm, O);
	}

	public static int overlayWidth() {
		return shm.get(JAVA_INT, O + 0x08);
	}

	public static int overlayHeight() {
		return shm.get(JAVA_INT, O + 0x0C);
	}

	/** Copy the newest overlay frame (RGBA, width * height * 4 bytes) into dst. */
	public static void copyOverlay(MemorySegment dst, long bytes, int front) {
		MemorySegment.copy(shm, O_PIXELS + front * OVERLAY_SLOT_BYTES, dst, 0, Math.min(bytes, OVERLAY_SLOT_BYTES));
	}

	public static boolean guestAlive() {
		if (shm == null) {
			return false;
		}
		long beat = (long) LONG.getAcquire(shm, H_GUEST_HEARTBEAT);
		return beat != 0 && tickCount() - beat < HEARTBEAT_TIMEOUT_MS;
	}

	public static int guestPid() {
		return shm == null ? 0 : shm.get(JAVA_INT, H_GUEST_PID);
	}

	/** TF2's latest player state, or null if there is none yet. */
	public static PlayerState readPlayer() {
		if (shm == null) {
			return null;
		}
		for (int attempt = 0; attempt < 100; attempt++) {
			int before = (int) INT.getAcquire(shm, P_SEQ);
			if ((before & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int flags = shm.get(JAVA_INT, P_FLAGS);
			PlayerState s = new PlayerState(
				shm.get(JAVA_LONG, P_SAMPLE),
				shm.get(JAVA_DOUBLE, P_X), shm.get(JAVA_DOUBLE, P_Y), shm.get(JAVA_DOUBLE, P_Z),
				shm.get(JAVA_FLOAT, P_YAW), shm.get(JAVA_FLOAT, P_PITCH), shm.get(JAVA_FLOAT, P_EYE)
			);
			VarHandle.acquireFence();
			int after = (int) INT.getAcquire(shm, P_SEQ);
			if (before == after) {
				return (flags & PLAYER_VALID) != 0 ? s : null;
			}
		}
		return null;
	}

	/** Milliseconds on the clock both games use for heartbeats: GetTickCount64 on Windows, CLOCK_MONOTONIC on Linux. */
	private static long tickCount() {
		if (!WINDOWS) {
			return System.nanoTime() / 1_000_000L;  // OpenJDK on Linux reads CLOCK_MONOTONIC, as TF2's side does
		}
		try {
			return (long) GET_TICK_COUNT64.invokeExact();
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}
}
