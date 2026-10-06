// FortCraft shared-memory protocol (Minecraft Fabric mod <-> TF2 Source SDK mod).
//
// This header is the single source of truth for the byte layout. It is mirrored in
// tools/fortlink.py (Python stand-ins) and fabric/src/main/java/dev/fortcraft/link/FortLink.java;
// if you change anything here, change it there too and bump kVersion.
//
// All multi-byte values are little-endian. Minecraft (the host) creates the mapping; TF2 (the
// hidden guest) opens it. Positions are always in Minecraft space (blocks, Y up); the TF2 side
// converts from its own units before writing.
#pragma once

#include <cstdint>

namespace fortcraft { namespace proto
{
	static constexpr std::uint32_t kMagic = 0x46524346;  // "FCRF"
	static constexpr std::uint32_t kVersion = 38;
	static constexpr wchar_t       kMappingName[] = L"Local\\FortCraft_v1";
	// Sized for the block boxes plus two overlay frames up to 4K; see kOffOverlayPixels below.
	static constexpr std::uint64_t kMappingBytes = 0x100000 + 2ull * 3840 * 2160 * 4;

	// A side counts as gone when its heartbeat is older than this.
	static constexpr std::uint64_t kHeartbeatTimeoutMs = 1000;

	static constexpr std::uint64_t kOffHeader = 0x0;
	static constexpr std::uint64_t kOffPlayerState = 0x100;
	static constexpr std::uint64_t kOffInputState = 0x140;
	static constexpr std::uint64_t kOffAnchor = 0x180;
	static constexpr std::uint64_t kOffOverlay = 0x200;
	static constexpr std::uint64_t kOffBlockBoxes = 0x1000;
	static constexpr std::uint64_t kOffOverlayPixels = 0x100000;

	// ---- header @0x0 ------------------------------------------------------------------------
	// Minecraft writes magic, version, hostPid and the host fields. TF2 checks magic and
	// version, then writes guestPid and the guest fields. Each side only writes its own fields.
	struct Header
	{
		std::uint32_t magic;
		std::uint32_t version;
		std::uint32_t hostPid;            // Minecraft process id
		std::uint32_t guestPid;           // TF2 process id; 0 until TF2 connects
		std::uint64_t hostHeartbeatMs;    // GetTickCount64() at Minecraft's last frame
		std::uint64_t guestHeartbeatMs;   // GetTickCount64() at TF2's last frame
		std::uint64_t hostFrame;          // counts up once per Minecraft frame
		std::uint64_t guestFrame;         // counts up once per TF2 frame
	};
	static_assert(sizeof(Header) == 0x30);

	// ---- TF2 -> Minecraft player state @0x100 -----------------------------------------------
	// TF2 decides where the player is. TF2 writes this once per TF2 render frame, using its
	// interpolated (between-ticks) position, not the raw tick position. Minecraft reads it each
	// render frame and moves its player there.
	//
	// Seqlock: the writer makes seq odd, writes the fields, then makes seq even. A reader that
	// sees an odd seq, or a seq that changed while it read, tries again.
	enum PlayerFlags : std::uint32_t
	{
		kPlayerValid = 1u << 0,  // the fields hold a real position
	};

	struct PlayerState
	{
		std::uint32_t seq;
		std::uint32_t flags;    // PlayerFlags
		std::uint64_t sample;   // counts up once per write; matches the "sample=" in both logs
		double        x, y, z;  // feet position, Minecraft coordinates
		float         yaw;      // Minecraft degrees
		float         pitch;    // Minecraft degrees
		float         eyeHeight; // TF2's eye above the feet, blocks; Minecraft puts its camera there
		std::uint32_t pad;
	};
	static_assert(sizeof(PlayerState) == 0x38);

	// ---- Minecraft -> TF2 input @0x140 ------------------------------------------------------
	// The Minecraft window has the keyboard and mouse, so Minecraft forwards what the player is
	// pressing, once per Minecraft render frame. For now Minecraft also owns the look direction
	// (its mouse turns the camera) and TF2 moves relative to it. Seqlock like PlayerState.
	enum InputButtons : std::uint32_t
	{
		kInForward = 1u << 0,
		kInBack = 1u << 1,
		kInLeft = 1u << 2,
		kInRight = 1u << 3,
		kInJump = 1u << 4,
		kInCrouch = 1u << 5,
		kInAttack = 1u << 6,  // fire (Minecraft's attack button)
		kInReload = 1u << 7,
		kInAttack2 = 1u << 8,  // secondary fire; Minecraft's use button when not aimed at a door, chest...
	};

	struct InputState
	{
		std::uint32_t seq;
		std::uint32_t buttons;  // InputButtons; 0 while a Minecraft screen (menu, chat) is open
		std::uint64_t sample;   // Minecraft frame number
		float         yaw;      // Minecraft degrees
		float         pitch;    // Minecraft degrees
		std::uint32_t weaponSlot;  // Minecraft's selected hotbar slot (0, 1, 2...); TF2 switches to that weapon slot
		std::uint32_t pad;
	};
	static_assert(sizeof(InputState) == 0x20);

	// ---- TF2 -> both TF2 modules: coordinate anchor @0x180 ----------------------------------
	// The TF2 position (TF2 units) that corresponds to Minecraft (0.5, -60, 0.5): where the TF2
	// player first spawned. Written by the TF2 client; read by TF2's movement code (client and
	// server) to place Minecraft's block boxes in TF2's world. Scale: kUnitsPerBlock.
	static constexpr double kUnitsPerBlock = 48.0;

	struct Anchor
	{
		std::uint32_t valid;  // 1 once set
		std::uint32_t pad;
		double        x, y, z;
	};
	static_assert(sizeof(Anchor) == 0x20);

	// ---- Minecraft -> TF2: window size @0x1A0 -----------------------------------------------
	// Minecraft's framebuffer size in pixels. TF2 switches its (hidden) window to match, scaled
	// down to fit kMaxOverlayWidth x kMaxOverlayHeight, so the overlay is sharp and the right shape.
	static constexpr std::uint64_t kOffHostDisplay = 0x1A0;

	struct HostDisplay
	{
		std::uint32_t width;
		std::uint32_t height;
		std::uint32_t fpsLimit;  // Minecraft's frame rate limit; TF2 uses the same (0: unlimited)
		std::uint32_t flags;     // HostDisplayFlags
		float         fovY;      // Minecraft's current vertical field of view, degrees (changes when flying etc.)
		float         aspect;    // Minecraft's window width / height
		float         light;     // Minecraft's brightness at the player's eyes, 0 (dark) .. 1 (daylight);
		                         // TF2 dims its models (not the HUD) by it. 0 before Minecraft sends it.
	};

	enum HostDisplayFlags : std::uint32_t
	{
		// Minecraft has let go of the mouse (pause menu, chat, inventory, or its window isn't
		// focused). TF2 must not hold the cursor then.
		kHostCursorFree = 1u << 0,
		// Minecraft's player is in creative mode: the TF2 player takes no damage (god mode).
		kHostCreative = 1u << 1,
		// Minecraft can't open the GPU overlay's shared textures: TF2 must use the read-back path.
		kHostNoGpuOverlay = 1u << 2,
	};
	static_assert(sizeof(HostDisplay) == 0x1C);
	static_assert(kOffHostDisplay + sizeof(HostDisplay) <= 0x1C0);  // SpawnPoint follows

	// ---- Minecraft -> TF2 spawn point @0x1C0 ----------------------------------------------
	// Where TF2 should (re)spawn the player: on top of Minecraft's ground at the world's spawn
	// column, Minecraft coordinates (feet).
	static constexpr std::uint64_t kOffSpawnPoint = 0x1C0;

	struct SpawnPoint
	{
		std::uint32_t valid;
		float         x, y, z;
	};
	static_assert(sizeof(SpawnPoint) == 16);

	// ---- TF2 -> Minecraft camera @0x1D0 ---------------------------------------------------
	// The camera TF2 actually rendered with. While TF2 is in third person (taunting), Minecraft
	// puts its camera exactly here, so TF2's picture of your Soldier lines up with Minecraft's
	// world. Seqlock.
	static constexpr std::uint64_t kOffCamera = 0x1D0;

	enum CameraFlags : std::uint32_t
	{
		kCameraThirdPerson = 1u << 0,
		kCameraTauntMenu = 1u << 1,  // TF2's taunt menu is open
		kCameraTaunting = 1u << 2,   // the player is taunting
		kCameraUiOpen = 1u << 3,     // a TF2 menu that takes the mouse is open (class/team menu, main menu, loadout)
		kCameraMedigun = 1u << 4,   // living Medic has a Medigun equipped; Minecraft may heal passive mobs on attack
	};

	struct Camera
	{
		std::uint32_t seq;
		std::uint32_t flags;    // CameraFlags
		float         x, y, z;  // Minecraft coordinates
		float         yaw;      // Minecraft degrees
		float         pitch;    // Minecraft degrees
		float         zoom;     // TF2's zoom (Sniper scope): tan(fov/2) / tan(default fov/2); 1 = none
	};
	static_assert(sizeof(Camera) == 32);

	// 0x1F0..0x200: unused (was the single-slot UiCommand before version 14; see UiEvents).

	// ---- Minecraft -> TF2 block collision @0x1000 -------------------------------------------
	// Solid Minecraft blocks near the player, merged into boxes, in Minecraft block coordinates.
	// TF2's movement code collides with these as if they were world geometry. Minecraft rewrites
	// the whole list a few times a second. Seqlock: seq odd while writing.
	struct BlockBox
	{
		float minX, minY, minZ;
		float maxX, maxY, maxZ;
	};
	static_assert(sizeof(BlockBox) == 24);

	static constexpr std::uint32_t kMaxBlockBoxes = 8192;

	struct BlockBoxes
	{
		std::uint32_t seq;
		std::uint32_t count;  // boxes in use, at most kMaxBlockBoxes
		BlockBox      boxes[kMaxBlockBoxes];
	};
	static_assert(kOffBlockBoxes + sizeof(BlockBoxes) <= kOffOverlayPixels);

	// VisualBoxes (Minecraft -> TF2, seqlock like BlockBoxes): blocks you can see but walk through
	// (grass, flowers, crops, torches...), one box per block around its outline. TF2 doesn't
	// collide with them; it only draws them into its depth buffer so its rockets, buildings and
	// effects are hidden behind them.
	static constexpr std::uint64_t kOffVisualBoxes = 0x32000;
	static constexpr std::uint32_t kMaxVisualBoxes = 2300;

	struct VisualBoxes
	{
		std::uint32_t seq;
		std::uint32_t count;
		BlockBox      boxes[kMaxVisualBoxes];
	};
	static_assert(kOffVisualBoxes >= kOffBlockBoxes + sizeof(BlockBoxes));
	static_assert(kOffVisualBoxes + sizeof(VisualBoxes) <= 0x40000);  // Shots

	// ---- TF2 -> Minecraft projectiles @0x400 ------------------------------------------------
	// Rockets, grenades and other projectiles in flight, written every TF2 frame, so Minecraft
	// can show them (in Minecraft coordinates). Seqlock: seq odd while writing.
	static constexpr std::uint64_t kOffProjectiles = 0x400;
	static constexpr std::uint32_t kMaxProjectiles = 96;

	enum ProjectileKind : std::uint32_t
	{
		kProjOther = 0,
		kProjRocket = 1,
		kProjGrenade = 2,  // pipes and stickies
	};

	struct Projectile
	{
		std::uint32_t id;    // TF2 entity index
		std::uint32_t kind;  // ProjectileKind
		float         x, y, z;
	};
	static_assert(sizeof(Projectile) == 20);

	struct Projectiles
	{
		std::uint32_t seq;
		std::uint32_t count;
		Projectile    list[kMaxProjectiles];
	};
	static_assert(kOffProjectiles + sizeof(Projectiles) <= 0xC00);

	// ---- TF2 -> Minecraft explosions @0xC00 -------------------------------------------------
	// A ring of the last 32 explosions. TF2 writes slot (count % 32), then bumps count; Minecraft
	// shows every explosion between the count it saw last and the current one.
	static constexpr std::uint64_t kOffExplosions = 0xC00;
	static constexpr std::uint32_t kMaxExplosions = 32;

	struct Explosion
	{
		float x, y, z;   // Minecraft coordinates
		float radius;    // blocks; 0 = harmless blast (Rocket/Sticky Jumper): no mob or block damage
		float damage;    // actual TF2 base damage, before critical multiplier
		std::uint32_t flags; // ShotFlags critical/minicritical bits
	};

	struct Explosions
	{
		std::uint32_t count;  // total explosions so far
		std::uint32_t pad;
		Explosion     ring[kMaxExplosions];
	};
	static_assert(kOffExplosions + sizeof(Explosions) <= kOffBlockBoxes);

	// ---- TF2 -> Minecraft overlay @0x200, pixels @0x100000 ----------------------------------
	// TF2's weapon (viewmodel) and HUD, drawn by TF2 with its world blanked out, read back from
	// the GPU and written here as RGBA with alpha 0 wherever TF2 drew nothing. Minecraft draws it
	// full-screen on top of its own frame. Two pixel slots: TF2 fills the one that isn't front,
	// then makes it front.
	static constexpr std::uint32_t kMaxOverlayWidth = 3840;
	static constexpr std::uint32_t kMaxOverlayHeight = 2160;
	static constexpr std::uint64_t kOverlaySlotBytes = std::uint64_t(kMaxOverlayWidth) * kMaxOverlayHeight * 4;

	struct OverlayHeader
	{
		std::uint32_t seq;     // bumps every new frame
		std::uint32_t front;   // slot (0 or 1) holding the newest complete frame
		std::uint32_t width;   // pixels, at most kMaxOverlayWidth
		std::uint32_t height;
	};
	static_assert(sizeof(OverlayHeader) == 0x10);

	// ---- TF2 -> Minecraft overlay pose @0x240 -----------------------------------------------
	// The look direction an overlay frame was rendered with (Minecraft's yaw/pitch that TF2
	// used) and when, one per slot (CPU path) or shared texture (GPU path), written before that
	// slot becomes front. Minecraft draws its world with the same look, so TF2's buildings and
	// effects stay planted in Minecraft's world instead of swimming when the camera turns.
	static constexpr std::uint64_t kOffOverlayPose = 0x240;

	struct OverlayPose
	{
		float         yaw, pitch;  // Minecraft degrees
		std::uint64_t timeMs;      // GetTickCount64 when TF2 started rendering it
		float         x, y, z;     // TF2's eye (camera) position for it, Minecraft coordinates
		std::uint32_t hasPosition; // 1 when x, y, z are set
	};
	static_assert(sizeof(OverlayPose) == 32);
	static_assert(kOffOverlayPose + 2 * sizeof(OverlayPose) <= 0x300);

	// ---- TF2 -> Minecraft overlay on the GPU @0x300 -----------------------------------------
	// The fast path: no pixels through shared memory. TF2 (Direct3D 9Ex) renders the finished
	// overlay (RGBA, straight alpha) into one of two shared GPU textures; Minecraft opens them by
	// handle (OpenGL, WGL_NV_DX_interop) and copies the front one into its own texture on the
	// GPU. When 'valid' is 0, the CPU path above is used instead.
	static constexpr std::uint64_t kOffOverlayGpu = 0x300;

	struct OverlayGpu
	{
		std::uint32_t seq;        // bumps every new frame
		std::uint32_t front;      // which texture (0 or 1) holds the newest complete frame
		std::uint32_t width;      // both textures are this size
		std::uint32_t height;
		std::uint32_t valid;      // 1 while the textures below exist
		std::uint32_t generation; // bumps when the textures are re-created (resize): reopen them
		std::uint64_t handle[2];  // Direct3D 9Ex shared handles (A8R8G8B8, D3DPOOL_DEFAULT)
	};
	static_assert(sizeof(OverlayGpu) == 0x28);
	static_assert(kOffOverlayPixels + 2 * kOverlaySlotBytes <= kMappingBytes);

	// ---- TF2 -> Minecraft shots @0x40000 ---------------------------------------------------
	// Every bullet (each shotgun pellet separately) and every melee swing, from TF2's server,
	// so Minecraft can hit its own mobs with them. Ring of the last 64: TF2 writes slot
	// (count % 64), then bumps count. Minecraft coordinates. Damage is TF2 damage.
	static constexpr std::uint64_t kOffShots = 0x40000;
	static constexpr std::uint32_t kMaxShots = 64;

	struct Shot
	{
		float ox, oy, oz;  // start
		float dx, dy, dz;  // direction (unit length)
		float range;       // blocks
		float damage;      // TF2 damage points
		std::uint32_t flags; // melee=1, critical=2, mini=4, headshot eligible=8, knife=16
		std::uint32_t weapon; // TF2 weapon ID, for diagnostics
		std::uint32_t tool; // generic=0, shovel=1, pickaxe=2, axe=3, blade=4
		float headshotRange; // blocks from shot origin; 0 means no extra limit
	};
	static_assert(sizeof(Shot) == 48);

	struct Shots
	{
		std::uint32_t count;
		std::uint32_t pad;
		Shot          ring[kMaxShots];
	};
	static_assert(kOffShots + sizeof(Shots) <= 0x41000);

	// ---- Minecraft -> TF2 damage to the player @0x41000 ------------------------------------
	// Damage Minecraft would have done to its player (mob hits, lava, ...), after Minecraft's own
	// hit cooldown (half a second). Minecraft cancels it on its side and TF2 applies it to the TF2
	// player instead (TF2 health is the real one), scaled so 20 Minecraft health = full TF2 health.
	// Ring of the last 32, same scheme as Shots.
	static constexpr std::uint64_t kOffHurts = 0x41000;
	static constexpr std::uint32_t kMaxHurts = 32;

	struct Hurt
	{
		float amount;            // Minecraft damage points (2 = one heart)
		float fromX, fromY, fromZ; // where it came from, Minecraft coordinates (for direction)
	};
	static_assert(sizeof(Hurt) == 16);

	struct Hurts
	{
		std::uint32_t count;
		std::uint32_t pad;
		Hurt          ring[kMaxHurts];
	};
	static_assert(kOffHurts + sizeof(Hurts) <= kOffOverlayPixels);

	// ---- Minecraft -> TF2 mob hitboxes @0x42000 --------------------------------------------
	// Living mobs near the player (Minecraft block coordinates), rewritten every Minecraft frame.
	// TF2's collision checks treat them as solid, so rockets explode on a direct hit and
	// bullets stop at the mob (Minecraft applies the damage). Nothing gets stuck inside one.
	// Seqlock like BlockBoxes.
	static constexpr std::uint64_t kOffMobBoxes = 0x42000;
	static constexpr std::uint32_t kMaxMobBoxes = 128;

	struct MobBoxes
	{
		std::uint32_t seq;
		std::uint32_t count;
		BlockBox      boxes[kMaxMobBoxes];
	};
	static_assert(kOffMobBoxes + sizeof(MobBoxes) <= kOffOverlayPixels);

	// Written with MobBoxes under the same seq: 1 if mob i is hostile (Minecraft's Enemy, e.g.
	// zombies and slimes), 0 for animals and villagers. Engineer sentries shoot hostile mobs.
	static constexpr std::uint64_t kOffMobHostile = 0x42E00;
	static_assert(kOffMobHostile >= kOffMobBoxes + sizeof(MobBoxes));
	static_assert(kOffMobHostile + kMaxMobBoxes <= 0x43000);

	// ---- Minecraft -> TF2 UI events @0x43000 ----------------------------------------------
	// Everything Minecraft passes on to TF2's own menus and UI: menu keys (taunt menu, class
	// select, loadout, scoreboard, TF2's main menu) and, while a TF2 menu is open, the mouse and
	// keyboard. Ring of the last 256: Minecraft writes slot (count % 256), then bumps count.
	static constexpr std::uint64_t kOffUiEvents = 0x43000;
	static constexpr std::uint32_t kMaxUiEvents = 256;

	enum UiEventType : std::uint32_t
	{
		kUiEvCommand = 1,    // code: UiCommands
		kUiEvMouseMove = 2,  // x, y: cursor position, 0..1 across TF2's screen (left to right, top to bottom)
		kUiEvMouseDown = 3,  // code: 0 left, 1 right, 2 middle; x, y as for a move
		kUiEvMouseUp = 4,
		kUiEvWheel = 5,      // code: +1 up / -1 down (one notch each)
		kUiEvKeyDown = 6,    // code: USB HID / SDL scancode (what Minecraft's key events carry)
		kUiEvKeyUp = 7,
		kUiEvChar = 8,       // code: typed character (Unicode code point)
	};

	enum UiCommands : std::uint32_t
	{
		kUiTauntKey = 1,     // G: open TF2's taunt menu (or, while open, do the weapon taunt; while taunting, stop)
		kUiSlot1 = 2,        // number keys 1..8 while the taunt menu is open: kUiSlot1 + n - 1
		kUiCancel = 12,      // Q: close the taunt menu, or stop taunting
		kUiClassMenu = 20,   // ,: TF2's class select (changeclass)
		kUiTeamMenu = 21,    // .: TF2's team select (changeteam)
		kUiLoadout = 22,     // M: TF2's loadout / items (open_charinfo_direct)
		kUiScoresDown = 23,  // Tab held: TF2's scoreboard
		kUiScoresUp = 24,
		kUiMainMenu = 25,    // P: open / close TF2's main (pause) menu
		kUiCloseAll = 26,    // Esc in Minecraft while a TF2 menu is open: close every TF2 menu
		kUiBuildSlot1 = 30,  // fresh number-key press 1..4; TF2 uses it only while an Engineer PDA is active
		kUiVoiceMenu1 = 40,  // Z/X/C: TF2's three stock voice menus
		kUiVoiceSelect1 = 50,  // number keys 1..9 while a voice menu is open
		kUiVoiceCancel = 59, // 0 or Q: close the voice menu without speaking
	};

	struct UiEvent
	{
		std::uint32_t type;  // UiEventType
		std::int32_t  code;
		float         x, y;
	};
	static_assert(sizeof(UiEvent) == 16);

	struct UiEvents
	{
		std::uint32_t count;
		std::uint32_t pad;
		UiEvent       ring[kMaxUiEvents];
	};
	static_assert(kOffUiEvents >= kOffMobBoxes + sizeof(MobBoxes));
	static_assert(kOffUiEvents + sizeof(UiEvents) <= kOffOverlayPixels);

	// ---- Minecraft -> TF2 backpack stacks @0x45000 -------------------------------------------
	// Minecraft's inventory, one entry per item type (counts summed over all slots). TF2 shows
	// each as a local-only item on its backpack's last page; nothing is sent to Steam. icon is the
	// material TF2 loads ("backpack/fortcraft/<name>", empty if none): Minecraft writes its .vmt
	// and .vtf into mod_tf/materials before listing the stack. Strings are UTF-8, NUL-terminated.
	// Seqlock: seq odd while writing.
	static constexpr std::uint64_t kOffBackpack = 0x45000;
	static constexpr std::uint32_t kMaxBackpackStacks = 50;  // one backpack page

	struct BackpackStack
	{
		char          id[64];    // Minecraft item id, e.g. "minecraft:dirt"
		char          name[64];  // Minecraft's display name, e.g. "Dirt"
		char          icon[64];
		std::uint32_t count;
		std::uint32_t pad;
	};
	static_assert(sizeof(BackpackStack) == 200);

	struct Backpack
	{
		std::uint32_t seq;
		std::uint32_t count;
		BackpackStack stacks[kMaxBackpackStacks];
	};
	static_assert(kOffBackpack >= kOffUiEvents + sizeof(UiEvents));
	static_assert(kOffBackpack + sizeof(Backpack) <= kOffOverlayPixels);

	// ---- TF2 -> Minecraft hand request @0x48000 -------------------------------------------------
	// Backpack phase B: "Equip to hand" on a Minecraft item in TF2's backpack. Each request bumps
	// count; id is the Minecraft item id to hold, or empty to put it away.
	static constexpr std::uint64_t kOffHandRequest = 0x48000;

	struct HandRequest
	{
		std::uint32_t count;
		std::uint32_t pad;
		char          id[64];
	};
	static_assert(kOffHandRequest >= kOffBackpack + sizeof(Backpack));

	// ---- Minecraft -> TF2 hand state @0x48100 ---------------------------------------------------
	// What Minecraft's hand holds instead of TF2's weapon: while equipped, Minecraft draws the item
	// in hand and TF2 hides its own weapon. Minecraft writes id first, then equipped.
	static constexpr std::uint64_t kOffHandState = 0x48100;

	struct HandState
	{
		std::uint32_t equipped;  // 1 while a Minecraft item is held
		std::uint32_t pad;
		char          id[64];
	};
	static_assert(kOffHandState >= kOffHandRequest + sizeof(HandRequest));
	static_assert(kOffHandState + sizeof(HandState) <= kOffOverlayPixels);

	// ---- Minecraft -> TF2 craftable recipes @0x48200 --------------------------------------------
	// Backpack phase C: the Minecraft crafting recipes that fit a 2x2 grid and that the player's
	// inventory can make right now. TF2 lists them in its crafting screen. key is Minecraft's
	// recipe id; name the result ("Oak Planks x4"); inputs what it uses ("Oak Log x1");
	// resultId/resultIcon the result's Minecraft item id and backpack icon material (written by
	// Minecraft like a backpack icon); ingredients the item ids it uses, one per grid square used
	// ("" = unused). Seqlock: seq odd while writing.
	static constexpr std::uint64_t kOffRecipes = 0x48200;
	static constexpr std::uint32_t kMaxRecipes = 64;

	struct CraftRecipe
	{
		char key[64];
		char name[64];
		char inputs[128];
		char resultId[64];
		char resultIcon[64];
		char ingredients[4][64];
	};
	static_assert(sizeof(CraftRecipe) == 640);

	struct CraftRecipes
	{
		std::uint32_t seq;
		std::uint32_t count;
		CraftRecipe   recipes[kMaxRecipes];
	};
	static_assert(kOffRecipes >= kOffHandState + sizeof(HandState));

	// ---- TF2 -> Minecraft craft request @0x53000 ------------------------------------------------
	// Each request bumps count; key is the recipe to craft once.
	static constexpr std::uint64_t kOffCraftRequest = 0x53000;

	struct CraftRequest
	{
		std::uint32_t count;
		std::uint32_t pad;
		char          key[64];
	};
	static_assert(kOffCraftRequest >= kOffRecipes + sizeof(CraftRecipes));

	// ---- Minecraft -> TF2 craft result @0x53100 -------------------------------------------------
	// After each craft request Minecraft sets ok, then bumps count (TF2 plays a sound on success).
	static constexpr std::uint64_t kOffCraftResult = 0x53100;

	struct CraftResult
	{
		std::uint32_t count;
		std::uint32_t ok;  // 1 if the last request crafted something
	};
	static_assert(kOffCraftResult >= kOffCraftRequest + sizeof(CraftRequest));
	static_assert(kOffCraftResult + sizeof(CraftResult) <= kOffOverlayPixels);

	// ---- TF2 -> Minecraft fire @0x53200 ---------------------------------------------------------
	// Where TF2's fire weapons touched the world or a mob (flamethrower flames, flares, Scorch Shot
	// and Detonator bursts, Dragon's Fury). Minecraft sets mobs within radius on fire and lights
	// one fire block on a flammable spot there; fire never breaks blocks. Ring of the last 64,
	// same scheme as Shots.
	static constexpr std::uint64_t kOffFires = 0x53200;
	static constexpr std::uint32_t kMaxFires = 64;

	struct FirePoint
	{
		float x, y, z;  // Minecraft coordinates
		float radius;   // blocks; negative = put fire out within -radius (Pyro airblast)
	};
	static_assert(sizeof(FirePoint) == 16);

	struct FirePoints
	{
		std::uint32_t count;
		std::uint32_t pad;
		FirePoint     ring[kMaxFires];
	};
	static_assert(kOffFires >= kOffCraftResult + sizeof(CraftResult));
	static_assert(kOffFires + sizeof(FirePoints) <= kOffOverlayPixels);

	// ---- Minecraft -> TF2 mob hits @0x53800 -----------------------------------------------------
	// Each time a TF2 attack actually hurt a Minecraft mob: where (top of the mob), how much (TF2
	// damage points) and whether it died. TF2 plays its hit sound and floats the number there, as
	// for a TF2 player. Ring of the last 32, same scheme as Shots.
	static constexpr std::uint64_t kOffMobHits = 0x53800;
	static constexpr std::uint32_t kMaxMobHits = 32;

	struct MobHit
	{
		float         x, y, z;  // Minecraft coordinates
		float         damage;   // TF2 damage points
		std::uint32_t killed;   // 1 if this hit killed it
		std::uint32_t pad;
	};
	static_assert(sizeof(MobHit) == 24);

	struct MobHits
	{
		std::uint32_t count;
		std::uint32_t pad;
		MobHit        ring[kMaxMobHits];
	};
	static_assert(kOffMobHits >= kOffFires + sizeof(FirePoints));
	static_assert(kOffMobHits + sizeof(MobHits) <= kOffOverlayPixels);

	// ---- TF2 -> Minecraft Engineer buildings @0x54000 -------------------------------------------
	// The player's buildings (sentry, dispenser, teleporters) as boxes in Minecraft coordinates,
	// so hostile mobs next to one can attack it. Seqlock: seq odd while writing.
	static constexpr std::uint64_t kOffBuildings = 0x54000;
	static constexpr std::uint32_t kMaxBuildings = 16;

	struct Building
	{
		std::uint32_t ent;   // TF2 entity index
		std::uint32_t pad;
		float         minX, minY, minZ, maxX, maxY, maxZ;
	};
	static_assert(sizeof(Building) == 32);

	struct Buildings
	{
		std::uint32_t seq;
		std::uint32_t count;
		Building      list[kMaxBuildings];
	};
	static_assert(kOffBuildings >= kOffMobHits + sizeof(MobHits));

	// ---- Minecraft -> TF2 hits on buildings @0x54400 --------------------------------------------
	// A hostile mob hit a building: entity index and the hit in Minecraft health points (TF2 takes
	// the same share of the building's health, as for the player). Ring of the last 32.
	static constexpr std::uint64_t kOffBuildingHits = 0x54400;
	static constexpr std::uint32_t kMaxBuildingHits = 32;

	struct BuildingHit
	{
		std::uint32_t ent;
		float         amount;
	};

	struct BuildingHits
	{
		std::uint32_t count;
		std::uint32_t pad;
		BuildingHit   ring[kMaxBuildingHits];
	};
	static_assert(kOffBuildingHits >= kOffBuildings + sizeof(Buildings));
	static_assert(kOffBuildingHits + sizeof(BuildingHits) <= kOffOverlayPixels);

	// ---- TF2 server <-> TF2 client: re-centring @0x54800 -----------------------------------------
	// TF2's playing field is the Source engine's maximum box (about 340 blocks each way). When the
	// player gets far from its middle, TF2's server moves the player, buildings and projectiles
	// back toward the middle by a whole number of blocks and adds that to its total here; the
	// coordinate anchor moves by the same amount, so nothing moves in Minecraft. The client
	// applies the shift when it sees its player jump, and records its own total; until then the
	// server uses anchor - (server total - client total).
	static constexpr std::uint64_t kOffRecentre = 0x54800;

	struct Recentre
	{
		double serverX, serverY;  // TF2 units, total moved so far by the server
		double clientX, clientY;  // TF2 units, total the client has applied to its anchor
	};
	static_assert(kOffRecentre >= kOffBuildingHits + sizeof(BuildingHits));
	static_assert(kOffRecentre + sizeof(Recentre) <= kOffOverlayPixels);

	// McTeleport (Minecraft -> TF2): Minecraft moved its player itself (a portal to the Nether or
	// the End, an ender pearl, /tp, a respawn in another dimension). TF2 puts its player there.
	// seq goes up by one per teleport; written after x, y, z.
	static constexpr std::uint64_t kOffMcTeleport = 0x54900;

	struct McTeleport
	{
		std::uint32_t seq;
		float x, y, z;  // Minecraft feet coordinates
	};
	static_assert(kOffMcTeleport >= kOffRecentre + sizeof(Recentre));

	// Minecraft -> TF2: the one friendly mob currently selected for native Medigun healing.
	// TF2 owns only an invisible presentation proxy; Minecraft owns the real mob and health.
	// proxyEntIndex is written by the TF2 server after it spawns its networked proxy.
	static constexpr std::uint64_t kOffMedicTarget = 0x55000;
	struct MedicTarget
	{
		std::uint32_t seq;
		std::int32_t entityId;  // Minecraft entity id; 0 means no target
		float x, y, z;         // Minecraft bounding-box centre
		float health, maxHealth; // Minecraft hearts converted to TF2 health units (x5)
		char name[64];         // UTF-8 Minecraft display name
		std::int32_t proxyEntIndex; // TF2 server writes; 0 while absent
	};
	static_assert(sizeof(MedicTarget) == 96);
	static_assert(kOffMedicTarget >= kOffMcTeleport + sizeof(McTeleport));
	static_assert(kOffMedicTarget + sizeof(MedicTarget) <= kOffOverlayPixels);

	// Minecraft water near the player, in block coordinates. The TF2 trace
	// wrapper exposes these as CONTENTS_WATER for its stock swimming movement.
	static constexpr std::uint64_t kOffWaterBoxes = 0x56000;
	static constexpr std::uint32_t kMaxWaterBoxes = 256;
	struct WaterBoxes
	{
		std::uint32_t seq;
		std::uint32_t count;
		BlockBox boxes[kMaxWaterBoxes];
	};
	static_assert(kOffWaterBoxes >= kOffMedicTarget + sizeof(MedicTarget));
	static_assert(kOffWaterBoxes + sizeof(WaterBoxes) <= kOffOverlayPixels);
	static_assert(kOffMcTeleport + sizeof(McTeleport) <= kOffOverlayPixels);

	// Minecraft damage = TF2 damage / kDamageScale (a rocket's 90 is 18 Minecraft health, nine
	// hearts; a zombie has 20).
	static constexpr float kDamageScale = 5.0f;
} }  // namespace fortcraft::proto
