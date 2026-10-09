package dev.fortcraft;

import static java.lang.foreign.ValueLayout.*;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.fortcraft.link.FortLink;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

/**
 * The fast overlay path: TF2 renders the finished weapon + HUD into Direct3D 9Ex textures it
 * shares by handle. We open them with a Direct3D 9Ex device of our own, expose them to OpenGL
 * with NVIDIA's WGL_NV_DX_interop, and copy the newest one into the overlay texture on the GPU.
 * No pixels pass through the CPU. If any step isn't available, {@link #usable()} turns false
 * and the shared-memory path is used instead.
 */
final class GpuOverlay {
	private static final int D3D_SDK_VERSION = 32;
	private static final int D3DDEVTYPE_HAL = 1;
	private static final int D3DCREATE_FPU_PRESERVE = 0x2, D3DCREATE_MULTITHREADED = 0x4, D3DCREATE_SOFTWARE_VERTEXPROCESSING = 0x20,
		D3DCREATE_NOWINDOWCHANGES = 0x800;
	private static final int D3DSWAPEFFECT_DISCARD = 1;
	private static final int D3DUSAGE_RENDERTARGET = 1, D3DFMT_A8R8G8B8 = 21, D3DPOOL_DEFAULT = 0;
	private static final int WGL_ACCESS_READ_ONLY_NV = 0;

	// vtable slots
	private static final int IUNKNOWN_RELEASE = 2;
	private static final int D3D9EX_CREATE_DEVICE_EX = 20;
	private static final int DEVICE_CREATE_TEXTURE = 23;

	private static final Linker LINKER = Linker.nativeLinker();

	// 0 untried, 1 ready, 2 failed. Direct3D sharing exists only on Windows; on Linux TF2 sends
	// pixels through shared memory from the start (see docs/DESIGN.md, "Linux port").
	private static int state = FortLink.WINDOWS ? 0 : 2;
	private static MemorySegment device = MemorySegment.NULL;
	private static long interopDevice;

	private static int generation = -1;
	private static final MemorySegment[] d3dTex = { MemorySegment.NULL, MemorySegment.NULL };
	private static final int[] glTex = new int[2];
	private static final long[] interopObj = new long[2];
	private static int width, height;

	private GpuOverlay() {
	}

	/** True once the GPU way has failed for good (TF2 is then told to send pixels instead). */
	public static boolean failed() {
		return state == 2;
	}

	static boolean usable() {
		return state != 2;
	}

	/** Copy TF2's newest GPU overlay frame into target. True if it did (target is then current). */
	static boolean copyInto(DynamicTexture target, int front, int gen, int w, int h, long handle0, long handle1) {
		if (state == 0) {
			init();
		}
		if (state != 1) {
			return false;
		}
		try {
			if (gen != generation) {
				open(gen, w, h, new long[] { handle0, handle1 });
			}
			if (interopObj[front] == 0 || !(target.getTexture() instanceof GlTexture gl)) {
				return false;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer obj = stack.pointers(interopObj[front]);
				if (!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, obj)) {
					return false;
				}
				GL43C.glCopyImageSubData(glTex[front], GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, gl.glId(), GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
				WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, obj);
			}
			return true;
		} catch (Throwable t) {
			fail("copy failed: " + t);
			return false;
		}
	}

	private static void init() {
		state = 2;
		try {
			SymbolLookup d3d9 = SymbolLookup.libraryLookup("d3d9", Arena.global());
			SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
			MethodHandle create = LINKER.downcallHandle(d3d9.find("Direct3DCreate9Ex").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
			MethodHandle desktop = LINKER.downcallHandle(user32.find("GetDesktopWindow").orElseThrow(), FunctionDescriptor.of(ADDRESS));

			try (Arena arena = Arena.ofConfined()) {
				MemorySegment out = arena.allocate(ADDRESS);
				int hr = (int) create.invokeExact(D3D_SDK_VERSION, out);
				MemorySegment d3d = out.get(ADDRESS, 0);
				if (hr < 0 || d3d.address() == 0) {
					fail("Direct3DCreate9Ex failed: " + Integer.toHexString(hr));
					return;
				}
				MemorySegment hwnd = (MemorySegment) desktop.invokeExact();

				// D3DPRESENT_PARAMETERS (64 bytes on x64): a 1x1 windowed device, only for opening textures.
				MemorySegment pp = arena.allocate(64);
				pp.set(JAVA_INT, 0, 1);
				pp.set(JAVA_INT, 4, 1);
				pp.set(JAVA_INT, 24, D3DSWAPEFFECT_DISCARD);
				pp.set(ADDRESS, 32, hwnd);
				pp.set(JAVA_INT, 40, 1);  // Windowed
				MemorySegment devOut = arena.allocate(ADDRESS);
				MethodHandle createDeviceEx = method(d3d, D3D9EX_CREATE_DEVICE_EX,
					FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
				hr = (int) createDeviceEx.invokeExact(d3d, 0, D3DDEVTYPE_HAL, hwnd,
					D3DCREATE_SOFTWARE_VERTEXPROCESSING | D3DCREATE_MULTITHREADED | D3DCREATE_FPU_PRESERVE | D3DCREATE_NOWINDOWCHANGES,
					pp, MemorySegment.NULL, devOut);
				release(d3d);
				device = devOut.get(ADDRESS, 0);
				if (hr < 0 || device.address() == 0) {
					fail("CreateDeviceEx failed: " + Integer.toHexString(hr));
					return;
				}
			}

			GL.createCapabilitiesWGL();
			interopDevice = WGLNVDXInterop.wglDXOpenDeviceNV(device.address());
			if (interopDevice == 0) {
				fail("wglDXOpenDeviceNV failed (no WGL_NV_DX_interop?)");
				return;
			}
			state = 1;
			FortCraft.LOG.info("FortCraft: GPU overlay ready (Direct3D 9Ex + WGL_NV_DX_interop)");
		} catch (Throwable t) {
			fail("setup failed: " + t);
		}
	}

	private static void open(int gen, int w, int h, long[] handles) throws Throwable {
		close();
		generation = gen;
		width = w;
		height = h;
		MethodHandle createTexture = method(device, DEVICE_CREATE_TEXTURE,
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
		try (Arena arena = Arena.ofConfined()) {
			for (int i = 0; i < 2; i++) {
				MemorySegment texOut = arena.allocate(ADDRESS);
				MemorySegment handle = arena.allocate(ADDRESS);
				handle.set(ADDRESS, 0, MemorySegment.ofAddress(handles[i]));  // non-null handle: open, don't create
				int hr = (int) createTexture.invokeExact(device, w, h, 1, D3DUSAGE_RENDERTARGET, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, texOut, handle);
				d3dTex[i] = texOut.get(ADDRESS, 0);
				if (hr < 0 || d3dTex[i].address() == 0) {
					throw new IllegalStateException("opening TF2's shared texture failed: " + Integer.toHexString(hr));
				}
				if (!WGLNVDXInterop.wglDXSetResourceShareHandleNV(d3dTex[i].address(), handles[i])) {
					throw new IllegalStateException("wglDXSetResourceShareHandleNV failed");
				}
				glTex[i] = GL11C.glGenTextures();
				interopObj[i] = WGLNVDXInterop.wglDXRegisterObjectNV(interopDevice, d3dTex[i].address(), glTex[i], GL11C.GL_TEXTURE_2D, WGL_ACCESS_READ_ONLY_NV);
				if (interopObj[i] == 0) {
					throw new IllegalStateException("wglDXRegisterObjectNV failed");
				}
			}
		}
		FortCraft.LOG.info("FortCraft: opened TF2's GPU overlay textures {}x{} (generation {})", w, h, gen);
	}

	private static void close() {
		for (int i = 0; i < 2; i++) {
			if (interopObj[i] != 0) {
				WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, interopObj[i]);
				interopObj[i] = 0;
			}
			if (glTex[i] != 0) {
				GL11C.glDeleteTextures(glTex[i]);
				glTex[i] = 0;
			}
			if (d3dTex[i].address() != 0) {
				try {
					release(d3dTex[i]);
				} catch (Throwable ignored) {
				}
				d3dTex[i] = MemorySegment.NULL;
			}
		}
	}

	private static void fail(String why) {
		state = 2;
		close();
		FortCraft.LOG.warn("FortCraft: GPU overlay not available ({}); using the shared-memory copy", why);
	}

	/** A COM method: slot 'index' of the object's vtable. */
	private static MethodHandle method(MemorySegment object, int index, FunctionDescriptor descriptor) {
		MemorySegment vtable = object.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * (index + 1));
		return LINKER.downcallHandle(vtable.getAtIndex(ADDRESS, index), descriptor);
	}

	private static void release(MemorySegment object) throws Throwable {
		MethodHandle rel = method(object, IUNKNOWN_RELEASE, FunctionDescriptor.of(JAVA_INT, ADDRESS));
		int ignored = (int) rel.invokeExact(object);
	}
}
