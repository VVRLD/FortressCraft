// FortCraft: the TF2 end of the shared-memory link. See fortcraft_link.h and
// protocol/fortcraft_protocol.h (the byte layout, which this file must match).
#include "cbase.h"
#include "fortcraft_link.h"
#include "c_tf_player.h"
#include "in_buttons.h"
#include "usercmd.h"
#include "tier0/icommandline.h"
#include "materialsystem/imaterialsystem.h"
#include "game/client/iviewport.h"
#include "tier0/threadtools.h"
#include "iinput.h"
#include "fortcraft_gpu.h"
#include "vgui/ISurface.h"
#include "fortcraft_collision.h"
#include "materialsystem/imesh.h"
#include "engine/ivmodelinfo.h"
#include "model_types.h"
#include "ienginevgui.h"
#include "vgui/IInputInternal.h"
#include "vgui/IInput.h"
#include "vgui/IPanel.h"
#include "vgui_controls/Controls.h"
#include "viewport_panel_names.h"
#include "tf_item_inventory.h"
#include "econ_item.h"
#include "econ_wearable.h"
#include "econ_ui.h"
#include "ivieweffects.h"
#include "shake.h"
#include "backpack_panel.h"
#include "tf_hud_menu_eureka_teleport.h"
#include "player_vs_environment/c_tf_upgrades.h"
#include "particle_parse.h"
#include "engine/IEngineSound.h"
#include "bone_setup.h"
#include "filesystem.h"
#include "props_shared.h"
#include "vgui_controls/Panel.h"
#include "vgui/IScheme.h"
#include "vgui_controls/Frame.h"
#include "confirm_dialog.h"
#include "hud_basedeathnotice.h"
#include "../protocol/fortcraft_protocol.h"
#include "fortcraft_platform.h"

#include <stdio.h>

// memdbgon must be the last include file in a .cpp file!!!
#include "tier0/memdbgon.h"

// The Eureka Effect's "teleport to spawn or exit" menu is showing.
static bool EurekaMenuOpen()
{
	CHudEurekaEffectTeleportMenu *pMenu = GET_HUDELEMENT( CHudEurekaEffectTeleportMenu );
	return pMenu && pMenu->IsVisible();
}

namespace proto = fortcraft::proto;

extern vgui::IInputInternal *g_InputInternal;  // TF2's UI input, set up in vgui_int.cpp

static const float UNITS_PER_BLOCK = (float)fortcraft::proto::kUnitsPerBlock;
static const double MC_FLOOR_Y = -60.0;            // TF2 spawn height lands on a flat world's surface

// Layout of the input slot (protocol InputState) and its button bits.
static const uint64_t kOffInput = proto::kOffInputState;

static unsigned char *s_pShm;
static double s_flLastOpenTry;
static bool s_bLinked;
static uint64_t s_nFrame;
static uint64_t s_nSample;
static FILE *s_pLog;

// TF2 position that maps to Minecraft's (0.5, -60, 0.5): the first place we spawn.
static bool s_bHaveOrigin;
static Vector s_vecOrigin;
static char s_szOriginMap[ MAX_PATH ];

static double s_flLastJoinTry;
static bool s_bHidden;
static double s_flRehideAt;

static void PlatLog( const char *pszLine ) { Msg( "%s", pszLine ); }

template < typename T > static T &At( uint64_t off ) { return *reinterpret_cast< T * >( s_pShm + off ); }

static proto::Header &Hdr() { return At< proto::Header >( proto::kOffHeader ); }

static bool HostAlive()
{
	uint64_t beat = *(volatile uint64_t *)&Hdr().hostHeartbeatMs;
	return beat != 0 && FortCraftPlat_NowMs() - beat < proto::kHeartbeatTimeoutMs;
}

static void TryOpen()
{
	if ( s_pShm )
		return;
	if ( Plat_FloatTime() - s_flLastOpenTry < 1.0 )
		return;
	s_flLastOpenTry = Plat_FloatTime();

	void *pView = FortCraftPlat_OpenLink( (size_t)proto::kMappingBytes );
	if ( !pView )
		return;  // Minecraft isn't running yet
	s_pShm = (unsigned char *)pView;
}

// Periodic measurements (perf, depth layer, jitter, far objects) go to logs/tf2_perf.log, not
// TF2's console: Alex found them filling the console (2026-10-09). tools/analyze_jitter.py reads it.
static void PerfLog( const char *fmt, ... )
{
	static FILE *s_pPerf;
	if ( !s_pPerf )
	{
		const char *pszDir = CommandLine()->ParmValue( "-fortcraft_logs", "." );
		char szPath[ MAX_PATH ];
		V_snprintf( szPath, sizeof( szPath ), "%s/tf2_perf.log", pszDir );
		s_pPerf = fopen( szPath, "w" );
		if ( !s_pPerf )
			return;
	}
	va_list args;
	va_start( args, fmt );
	vfprintf( s_pPerf, fmt, args );
	va_end( args );
	fflush( s_pPerf );
}

static void LinkLog( const char *fmt, ... )
{
	if ( !s_pLog )
	{
		const char *pszDir = CommandLine()->ParmValue( "-fortcraft_logs", "." );
		char szPath[ MAX_PATH ];
		V_snprintf( szPath, sizeof( szPath ), "%s/tf2_sent.log", pszDir );
		s_pLog = fopen( szPath, "w" );
		if ( !s_pLog )
			return;
	}
	va_list args;
	va_start( args, fmt );
	vfprintf( s_pLog, fmt, args );
	va_end( args );
	fflush( s_pLog );
}

// VGUI reads the Windows cursor relative to TF2's hidden client area. Video-mode
// changes resized that client to Minecraft's 1920x1080 but left its old (320,191)
// desktop origin, exactly matching Alex's hover/drag offset. Align the origins,
// not the pointer, and only when both client areas really are the same size.
static void AlignHiddenWindowToMinecraft()
{
	if ( !s_bHidden || !s_pShm )
		return;
	static double s_flNextCheck;
	if ( Plat_FloatTime() < s_flNextCheck )
		return;
	s_flNextCheck = Plat_FloatTime() + 0.5;
	const proto::HostDisplay &display = At< proto::HostDisplay >( proto::kOffHostDisplay );
	if ( display.width <= 0 || display.height <= 0 || ScreenWidth() != display.width || ScreenHeight() != display.height )
		return;
	FortCraftPlat_AlignToHost( Hdr().hostPid, display.width, display.height, PlatLog );
}

static bool UiOpen();

// Report what TF2 has actually equipped. This is diagnostic evidence, not a claim
// that Minecraft displayed the model, paint, particle effect, or animation.
static void AuditEquippedContent( C_TFPlayer *pPlayer, C_TFWeaponBase *pActive )
{
	static float s_flNextCheck;
	static unsigned int s_nPreviousSignature;
	static bool s_bHadPlayer;
	if ( gpGlobals->curtime < s_flNextCheck )
		return;
	s_flNextCheck = gpGlobals->curtime + 2.0f;
	if ( !pPlayer )
	{
		s_bHadPlayer = false;
		return;
	}
	unsigned int signature = 2166136261u;
	const int weaponEntity = pActive ? pActive->entindex() : -1;
	CEconItemView *pWeaponItem = pActive ? pActive->GetAttributeContainer()->GetItem() : NULL;
	signature = ( signature ^ (unsigned int)weaponEntity ) * 16777619u;
	signature = ( signature ^ (unsigned int)( pWeaponItem ? pWeaponItem->GetItemDefIndex() : -1 ) ) * 16777619u;
	signature = ( signature ^ (unsigned int)( pWeaponItem ? pWeaponItem->GetItemStyle() : -1 ) ) * 16777619u;
	const int count = pPlayer->GetNumWearables();
	for ( int i = 0; i < count; ++i )
	{
		C_EconWearable *pWearable = pPlayer->GetWearable( i );
		if ( !pWearable ) continue;
		CEconItemView *pItem = pWearable->GetAttributeContainer()->GetItem();
		signature = ( signature ^ (unsigned int)pWearable->entindex() ) * 16777619u;
		signature = ( signature ^ (unsigned int)( pItem ? pItem->GetItemDefIndex() : -1 ) ) * 16777619u;
		signature = ( signature ^ (unsigned int)( pItem ? pItem->GetItemQuality() : -1 ) ) * 16777619u;
		signature = ( signature ^ (unsigned int)( pItem ? pItem->GetItemStyle() : -1 ) ) * 16777619u;
		signature = ( signature ^ (unsigned int)pWearable->GetModelIndex() ) * 16777619u;
		signature = ( signature ^ (unsigned int)pWearable->IsEffectActive( EF_NODRAW ) ) * 16777619u;
	}
	if ( s_bHadPlayer && signature == s_nPreviousSignature ) return;
	s_bHadPlayer = true;
	s_nPreviousSignature = signature;
	Msg( "FortCraft content: active_weapon_entity=%d weapon_id=%d item_def=%d quality=%d style=%d wearables=%d\n",
		weaponEntity, pActive ? pActive->GetWeaponID() : -1,
		pWeaponItem ? pWeaponItem->GetItemDefIndex() : -1,
		pWeaponItem ? pWeaponItem->GetItemQuality() : -1,
		pWeaponItem ? (int)pWeaponItem->GetItemStyle() : -1, count );
	for ( int i = 0; i < count; ++i )
	{
		C_EconWearable *pWearable = pPlayer->GetWearable( i );
		if ( !pWearable ) continue;
		CEconItemView *pItem = pWearable->GetAttributeContainer()->GetItem();
		Msg( "FortCraft content: wearable=%d item_def=%d quality=%d style=%d model=%d hidden=%d\n",
			i, pItem ? pItem->GetItemDefIndex() : -1,
			pItem ? pItem->GetItemQuality() : -1, pItem ? (int)pItem->GetItemStyle() : -1,
			pWearable->GetModelIndex(), pWearable->IsEffectActive( EF_NODRAW ) ? 1 : 0 );
	}
}

// The look each overlay was drawn with is sent with it, so Minecraft can draw its world the same
// way (see "Overlay" below).
static proto::OverlayPose s_LastLookPose;  // the look ApplyLatestLook last set, in Minecraft degrees, and when
static proto::OverlayPose s_PairPose, s_WorkPose;

// Creative flight (Alex, 2026-10-11), as in Minecraft: tap jump twice quickly to start or stop
// flying; the Scout taps three times, since two taps are its double jump. Landing stops it.
// Movement (tf_gamemovement.cpp, both modules) sees it as IN_BULLRUSH on the command, a button
// TF2 never uses, so the client's prediction and the server agree.
static void QuickSlotPressed( int nSlot );  // the Minecraft item key (below)

// A PDA is out: the Engineer's build or destroy PDA, or the Spy's disguise kit. Their number keys
// pick buildings or disguises, so Q and a right click put them away (Alex, 2026-10-13).
static bool IsPda( C_BaseCombatWeapon *pWeapon )
{
	C_TFWeaponBase *pTF = dynamic_cast< C_TFWeaponBase * >( pWeapon );
	const int id = pTF ? pTF->GetWeaponID() : TF_WEAPON_NONE;
	return id == TF_WEAPON_PDA_ENGINEER_BUILD || id == TF_WEAPON_PDA_ENGINEER_DESTROY || id == TF_WEAPON_PDA_SPY;
}
static bool QuickSlotHasItem( int nClass, int nSlot );
static bool CreativeFlight( bool bJump )
{
	static bool s_bFlying, s_bWasJump, s_bAirborne;
	static double s_flLastTap;
	static int s_nTaps;
	C_TFPlayer *pPlayer = C_TFPlayer::GetLocalTFPlayer();
	if ( !pPlayer || !pPlayer->IsAlive() || !FortCraft_HostCreative() )
	{
		s_bFlying = false;
		s_nTaps = 0;
		s_bWasJump = bJump;
		return false;
	}
	const int nTapsNeeded = pPlayer->IsPlayerClass( TF_CLASS_SCOUT ) ? 3 : 2;
	const double flNow = Plat_FloatTime();
	if ( bJump && !s_bWasJump )
	{
		if ( flNow - s_flLastTap > 0.35 )
			s_nTaps = 0;  // too slow: start counting again
		s_flLastTap = flNow;
		if ( ++s_nTaps >= nTapsNeeded )
		{
			s_nTaps = 0;
			s_bFlying = !s_bFlying;
			s_bAirborne = false;
			Msg( "FortCraft: creative flight %s\n", s_bFlying ? "on" : "off" );
		}
	}
	s_bWasJump = bJump;
	if ( s_bFlying )
	{
		if ( pPlayer->GetGroundEntity() == NULL )
			s_bAirborne = true;
		else if ( s_bAirborne && !bJump )
		{
			s_bFlying = false;  // landed
			Msg( "FortCraft: creative flight off (landed)\n" );
		}
	}
	return s_bFlying;
}

bool FortCraft_ApplyInput( CUserCmd *pCmd )
{
	if ( !s_bLinked )
		return false;

	// Seqlock read of Minecraft's input slot.
	proto::InputState in;
	for ( int attempt = 0; ; ++attempt )
	{
		uint32_t before = *(volatile uint32_t *)&At< proto::InputState >( kOffInput ).seq;
		in = At< proto::InputState >( kOffInput );
		uint32_t after = *(volatile uint32_t *)&At< proto::InputState >( kOffInput ).seq;
		if ( !( before & 1 ) && before == after )
			break;
		if ( attempt > 100 )
			return false;
	}

	// Minecraft yaw 0 faces +Z (south), which is TF2 -Y; TF2 yaw 0 faces +X.
	QAngle ang( in.pitch, AngleNormalize( -in.yaw - 90.0f ), 0.0f );
	pCmd->viewangles = ang;
	engine->SetViewAngles( ang );

	const float flSpeed = 450.0f;  // cl_forwardspeed / cl_sidespeed; the class max speed caps it
	int fwd = ( ( in.buttons & proto::kInForward ) ? 1 : 0 ) - ( ( in.buttons & proto::kInBack ) ? 1 : 0 );
	int right = ( ( in.buttons & proto::kInRight ) ? 1 : 0 ) - ( ( in.buttons & proto::kInLeft ) ? 1 : 0 );
	pCmd->forwardmove = fwd * flSpeed;
	pCmd->sidemove = right * flSpeed;
	pCmd->upmove = 0.0f;

	int buttons = 0;
	if ( in.buttons & proto::kInForward ) buttons |= IN_FORWARD;
	if ( in.buttons & proto::kInBack ) buttons |= IN_BACK;
	if ( in.buttons & proto::kInLeft ) buttons |= IN_MOVELEFT;
	if ( in.buttons & proto::kInRight ) buttons |= IN_MOVERIGHT;
	if ( in.buttons & proto::kInJump ) buttons |= IN_JUMP;
	if ( in.buttons & proto::kInCrouch ) buttons |= IN_DUCK;
	if ( in.buttons & proto::kInAttack ) buttons |= IN_ATTACK;
	if ( in.buttons & proto::kInReload ) buttons |= IN_RELOAD;
	if ( in.buttons & proto::kInAttack2 ) buttons |= IN_ATTACK2;
	if ( CreativeFlight( ( in.buttons & proto::kInJump ) != 0 ) )
		buttons |= IN_BULLRUSH;
	// Right click with a PDA out: put it away (it has no right-click use).
	static bool s_bAttack2Was;
	const bool bAttack2 = ( buttons & IN_ATTACK2 ) != 0;
	C_TFPlayer *pPdaPlayer = C_TFPlayer::GetLocalTFPlayer();
	if ( pPdaPlayer && IsPda( pPdaPlayer->GetActiveWeapon() ) && !UiOpen() )
	{
		if ( bAttack2 && !s_bAttack2Was )
		{
			engine->ClientCmd( "lastinv\n" );
			Msg( "FortCraft: right click put the PDA away\n" );
		}
		buttons &= ~IN_ATTACK2;
	}
	s_bAttack2Was = bAttack2;
	pCmd->buttons = buttons;

	// Minecraft's hotbar slot picks the TF2 weapon slot (1: primary, 2: secondary, 3: melee...).
	// Retry a missed request, but stop after it succeeds so TF2 can keep its builder equipped.
	static int s_nLastSlot = -1;
	static int s_nLastClass = -1;
	static int s_nLastDesiredWeapon = -1;
	static bool s_bSwitchPending = false;
	bool bNewSlot = false;
	if ( (int)in.weaponSlot != s_nLastSlot )
	{
		s_nLastSlot = (int)in.weaponSlot;
		s_bSwitchPending = true;
		bNewSlot = true;
		Msg( "FortCraft weapon requested: slot=%d\n", s_nLastSlot + 1 );
	}
	C_TFPlayer *pPlayer = C_TFPlayer::GetLocalTFPlayer();
	if ( pPlayer )
	{
		C_BaseCombatWeapon *pWeapon = NULL;
		for ( int i = 0; i < pPlayer->WeaponCount() && !pWeapon; ++i )
		{
			C_BaseCombatWeapon *pCandidate = pPlayer->GetWeapon( i );
			if ( pCandidate && pCandidate->GetSlot() == (int)in.weaponSlot )
				pWeapon = pCandidate;
		}
		const int nClass = pPlayer->GetPlayerClass() ? pPlayer->GetPlayerClass()->GetClassIndex() : -1;
		// Engineer's key 6 is also his toolbox slot (TF2 puts it in his hands only while he carries a
		// building), so it never counted as empty and his Minecraft item never came out (Alex,
		// 2026-10-12). A Minecraft item on the key wins over a toolbox, or the Spy's watch, there.
		if ( pWeapon && QuickSlotHasItem( nClass, (int)in.weaponSlot ) && dynamic_cast< C_TFWeaponBase * >( pWeapon ) )
		{
			const int nId = static_cast< C_TFWeaponBase * >( pWeapon )->GetWeaponID();
			if ( nId == TF_WEAPON_BUILDER || nId == TF_WEAPON_INVIS )
				pWeapon = NULL;
		}
		const int nDesiredWeapon = pWeapon ? pWeapon->entindex() : -1;
		if ( bNewSlot && !pWeapon )
			QuickSlotPressed( (int)in.weaponSlot );  // the Minecraft item key

		// Engineer: with the build or destroy PDA out, number keys 1-4 pick a building in TF2's
		// menu (sentry, dispenser, teleporter entrance, exit) instead of switching weapons.
		C_TFWeaponBase *pActive = dynamic_cast< C_TFWeaponBase * >( pPlayer->GetActiveWeapon() );
		const int nActiveId = pActive ? pActive->GetWeaponID() : TF_WEAPON_NONE;
		if ( ( ( nActiveId == TF_WEAPON_PDA_ENGINEER_BUILD || nActiveId == TF_WEAPON_PDA_ENGINEER_DESTROY )
			&& in.weaponSlot < 4 && !UiOpen() ) || EurekaMenuOpen() )
		{
			// The held slot is not a new press. Building choice arrives as an explicit UI event below.
			s_bSwitchPending = false;
			s_nLastClass = nClass;
			s_nLastDesiredWeapon = nDesiredWeapon;
			return true;
		}
		if ( nClass != s_nLastClass || nDesiredWeapon != s_nLastDesiredWeapon )
		{
			s_nLastClass = nClass;
			s_nLastDesiredWeapon = nDesiredWeapon;
			s_bSwitchPending = true;
		}
		if ( s_bSwitchPending && pWeapon == pPlayer->GetActiveWeapon() )
		{
			s_bSwitchPending = false;
			if ( pWeapon )
				Msg( "FortCraft weapon active: slot=%d ent=%d\n", s_nLastSlot + 1, nDesiredWeapon );
		}
		else if ( s_bSwitchPending && pWeapon && !UiOpen() )
		{
			pCmd->weaponselect = pWeapon->entindex();
			// TF2's server picks the weapon by name and subtype: all builders are
			// "tf_weapon_builder", told apart by subtype (the Spy's sapper is 3). Without it the
			// Spy's key 2 never brought out the sapper (Alex, 2026-10-12).
			pCmd->weaponsubtype = pWeapon->GetSubType();
		}
	}
	return true;
}

static void ToMinecraft( const Vector &pos, float &x, float &y, float &z );
static bool UiOpen();

// The camera TF2 renders with this frame, and whether it's a third-person one (taunts).
static void WriteCamera( const Vector &origin, const QAngle &angles )
{
	if ( !s_bHaveOrigin )
		return;
	C_TFPlayer *pPlayer = C_TFPlayer::GetLocalTFPlayer();
	// Taunts, and the death camera after dying (TF2 pulls back to show the body and the killer):
	// Minecraft follows TF2's camera exactly while either is on.
	bool bThird = pPlayer && ( pPlayer->m_Shared.InCond( TF_COND_TAUNTING ) || ( ::input && ::input->CAM_IsThirdPerson() )
		|| !pPlayer->IsAlive() || pPlayer->GetObserverMode() != OBS_MODE_NONE );
	C_TFWeaponBase *pActive = pPlayer ? pPlayer->GetActiveTFWeapon() : NULL;
	AuditEquippedContent( pPlayer, pActive );
	static bool s_bWasTaunting;
	const bool bTaunting = pPlayer && pPlayer->m_Shared.InCond( TF_COND_TAUNTING );
	if ( bTaunting != s_bWasTaunting )
	{
		s_bWasTaunting = bTaunting;
		Msg( "FortCraft content: taunt %s\n", bTaunting ? "started" : "ended" );
	}
	const bool bMedigun = pPlayer && pPlayer->IsAlive() && pPlayer->IsPlayerClass( TF_CLASS_MEDIC )
		&& pActive && pActive->GetWeaponID() == TF_WEAPON_MEDIGUN;
	proto::Camera &cam = At< proto::Camera >( proto::kOffCamera );
	uint32_t seq = cam.seq;
	*(volatile uint32_t *)&cam.seq = ( seq + 1 ) | 1;  // odd: writing
	cam.flags = ( bThird ? proto::kCameraThirdPerson : 0 )
		| ( pPlayer && pPlayer->ShouldShowHudMenuTauntSelection() ? proto::kCameraTauntMenu : 0 )
		| ( pPlayer && pPlayer->m_Shared.InCond( TF_COND_TAUNTING ) ? proto::kCameraTaunting : 0 )
		| ( UiOpen() ? proto::kCameraUiOpen : 0 )
		| ( bMedigun ? proto::kCameraMedigun : 0 )
		// Cloaked as TF2's sentries count it: more than 75% invisible (bumping or firing shows you).
		| ( pPlayer && pPlayer->IsAlive() && pPlayer->m_Shared.IsStealthed() && pPlayer->m_Shared.GetPercentInvisible() > 0.75f
			? proto::kCameraCloaked : 0 )
		| ( EurekaMenuOpen() ? proto::kCameraEurekaMenu : 0 )
		| ( engine->Con_IsVisible() ? proto::kCameraConsole : 0 )
		| ( pPlayer && !pPlayer->IsAlive() ? proto::kCameraDead : 0 )
		| ( IsPda( pActive ) ? proto::kCameraEngineerPda : 0 );
	ToMinecraft( origin, cam.x, cam.y, cam.z );
	cam.yaw = AngleNormalize( -angles.y - 90.0f );
	cam.pitch = angles.x;
	// Sniper scope and other zooms: how much narrower TF2's own view is than its default, so
	// Minecraft narrows its field of view by the same factor (and sends it back to us).
	float flZoom = 1.0f;
	if ( pPlayer && pPlayer->GetDefaultFOV() > 0 && pPlayer->GetFOV() > 0.0f && pPlayer->GetFOV() < pPlayer->GetDefaultFOV() )
		flZoom = tanf( DEG2RAD( pPlayer->GetFOV() ) * 0.5f ) / tanf( DEG2RAD( (float)pPlayer->GetDefaultFOV() ) * 0.5f );
	cam.zoom = flZoom;
	*(volatile uint32_t *)&cam.seq = ( ( seq + 1 ) | 1 ) + 1;  // even: done
	static bool s_bWasZoomed;
	if ( ( flZoom < 0.99f ) != s_bWasZoomed )
	{
		s_bWasZoomed = flZoom < 0.99f;
		Msg( "FortCraft: TF2 zoom %.2f (fov %.0f of %d)\n", flZoom, pPlayer ? pPlayer->GetFOV() : 0.0f, pPlayer ? pPlayer->GetDefaultFOV() : 0 );
	}
	static bool s_bWasThird;
	if ( bThird != s_bWasThird )
	{
		s_bWasThird = bThird;
		Msg( "FortCraft: TF2 camera now %s\n", bThird ? "third person (taunt or death camera)" : "first person" );
	}
}

// ---- TF2's own menus -------------------------------------------------------------------------
// Class and team select (viewport panels), and TF2's main menu and loadout (GameUI). While one
// is open, Minecraft shows TF2's whole frame, lets go of the mouse and forwards mouse and keys,
// which go straight into TF2's UI (vgui) as if TF2's own window had them.

// Set when Alex asked for a class or team menu; HideMenus leaves those alone. TF2's own popups
// (map info, team and class menus on joining) are still hidden: AutoJoin answers those.
static bool s_bUserMenu;
static double s_flBackpackLastPageUntil;  // E: turn the backpack to the Minecraft items until then
static double s_flUserMenuAt;

static bool ViewportMenuOpen()
{
	if ( !gViewPortInterface )
		return false;
	IViewPortPanel *pPanel = gViewPortInterface->GetActivePanel();
	return pPanel && pPanel->IsVisible() && V_strcmp( pPanel->GetName(), PANEL_SCOREBOARD ) != 0;
}

// MvM's upgrade station music, looping while the upgrade screen is open (Alex, 2026-10-10).
static void UpgradeMusic( bool bOpen )
{
	static const char *kMusic = "items/tf_music_upgrade_machine.wav";
	static bool s_bPlaying;
	static double s_flRestartAt;
	if ( !bOpen )
	{
		if ( s_bPlaying )
		{
			enginesound->EmitAmbientSound( kMusic, 0.0f, PITCH_NORM, SND_STOP );
			s_bPlaying = false;
		}
		return;
	}
	const double flNow = Plat_FloatTime();
	if ( s_bPlaying && flNow < s_flRestartAt )
		return;
	if ( s_bPlaying )
		enginesound->EmitAmbientSound( kMusic, 0.0f, PITCH_NORM, SND_STOP );
	enginesound->PrecacheSound( kMusic, true );
	enginesound->EmitAmbientSound( kMusic, 0.6f );
	const float flLength = enginesound->GetSoundDuration( kMusic );
	s_flRestartAt = flNow + ( flLength > 1.0f ? flLength : 30.0f );
	s_bPlaying = true;
}

// Cycles through n variations (1..n) from a random start, so the same one doesn't come back
// until all have played (Alex, 2026-10-11).
static int NextVariant( int &nLast, int n )
{
	if ( nLast < 0 )
		nLast = RandomInt( 0, n - 1 );
	nLast = ( nLast + 1 ) % n;
	return nLast + 1;
}

// One of the Administrator's MvM lines; the pools below join groups that mean the same thing.
static void PlayAdminLine( const char *pszGroup, int n, int &nLast )
{
	engine->ClientCmd_Unrestricted( VarArgs( "play vo/%s%02d.mp3\n", pszGroup, NextVariant( nLast, n ) ) );
}

// A village raid near the player, played like MvM's waves (Alex, 2026-10-11):
// - each raid wave's horn: a wave-start music, cycling MvM's variants (the final-wave one for the
//   last wave), with the Administrator's first-wave or final-wave line;
// - all of a wave's raiders dead with more waves to come: the wave-end music and her "prepare for
//   the next wave" line;
// - the raid won: the final-wave end music and her final-wave line; lost: the lost-wave ones.
// A start music still playing fades out over 3 s when its wave ends.
static void RaidMusic()
{
	static const char *s_pszMusic = "";
	static uint32_t s_nState, s_nWave, s_nAlive;
	static bool s_bPlaying;
	static double s_flRestartAt, s_flFadeStart = -1.0;
	const proto::RaidState &raid = At< proto::RaidState >( proto::kOffRaidState );
	const uint32_t state = raid.state, wave = raid.wave, total = raid.totalWaves, alive = raid.alive;
	const double flNow = Plat_FloatTime();

	if ( state == 1 && wave > s_nWave && wave > 0 )
	{
		// A new wave's horn.
		static const char *kMid[] = { "music/mvm_start_mid_wave.wav", "music/mvm_start_tank_wave.wav", "music/mvm_start_wave.wav" };
		const bool bLast = total > 0 && wave >= total;
		const char *pszMusic = bLast ? "music/mvm_start_last_wave.wav" : wave == 1 ? "music/mvm_start_wave.wav" : kMid[ ( wave - 2 ) % 3 ];
		if ( s_bPlaying )
			enginesound->EmitAmbientSound( s_pszMusic, 0.0f, PITCH_NORM, SND_STOP );
		s_pszMusic = pszMusic;
		enginesound->PrecacheSound( s_pszMusic, true );
		enginesound->EmitAmbientSound( s_pszMusic, 0.7f );
		const float flLength = enginesound->GetSoundDuration( s_pszMusic );
		s_flRestartAt = flNow + ( flLength > 1.0f ? flLength : 60.0f );  // when it ends (no loop)
		s_bPlaying = true;
		s_flFadeStart = -1.0;
		// The Administrator announces every wave: first-wave lines, then the general wave-start
		// lines (two groups, 20 lines), and final-wave lines for the last.
		static int s_nFirst = -1, s_nMid = -1, s_nFinal = -1;
		if ( bLast )
			PlayAdminLine( "mvm_final_wave_start", 12, s_nFinal );
		else if ( wave == 1 )
			PlayAdminLine( "mvm_firstwave_start", 6, s_nFirst );
		else
		{
			const int nLine = NextVariant( s_nMid, 20 );
			if ( nLine <= 12 )
				engine->ClientCmd_Unrestricted( VarArgs( "play vo/mvm_wave_start%02d.mp3\n", nLine ) );
			else
				engine->ClientCmd_Unrestricted( VarArgs( "play vo/mvm_general_wav_start%02d.mp3\n", nLine - 12 ) );
		}
		Msg( "FortCraft: village raid wave %u of %u: %s\n", wave, total, pszMusic );
	}
	else if ( state == 1 && wave > 0 && alive == 0 && s_nAlive > 0 && ( total == 0 || wave < total ) )
	{
		// Wave cleared, more to come.
		static const char *kEnd[] = { "music/mvm_end_wave.wav", "music/mvm_end_mid_wave.wav", "music/mvm_end_tank_wave.wav" };
		if ( s_bPlaying )
			s_flFadeStart = flNow;
		// Her "good job" lines: the wave-end ones and the bonus ones (11 in all), in turn.
		static int s_nEnd = -1;
		const int nLine = NextVariant( s_nEnd, 11 );
		engine->ClientCmd_Unrestricted( VarArgs( "play %s\n", kEnd[ ( wave - 1 ) % 3 ] ) );
		if ( nLine <= 8 )
			engine->ClientCmd_Unrestricted( VarArgs( "play vo/mvm_wave_end%02d.mp3\n", nLine ) );
		else
			engine->ClientCmd_Unrestricted( VarArgs( "play vo/mvm_bonus%02d.mp3\n", nLine - 8 ) );
		Msg( "FortCraft: village raid wave %u cleared\n", wave );
	}
	if ( state != s_nState && s_nState == 1 )
	{
		if ( s_bPlaying )
			s_flFadeStart = flNow;
		if ( state == 2 )
		{
			static int s_nFinalEnd = -1;
			engine->ClientCmd_Unrestricted( "play music/mvm_end_last_wave.wav\n" );
			PlayAdminLine( "mvm_final_wave_end", 6, s_nFinalEnd );
			Msg( "FortCraft: village defended\n" );
		}
		else if ( state == 3 )
		{
			static int s_nLose = -1;
			engine->ClientCmd_Unrestricted( "play music/mvm_lost_wave.wav\n" );
			PlayAdminLine( "mvm_wave_lose", 12, s_nLose );
			Msg( "FortCraft: village lost\n" );
		}
	}
	s_nState = state;
	s_nWave = state == 0 ? 0 : wave;
	s_nAlive = alive;
	const char *kMusic = s_pszMusic;
	if ( !s_bPlaying )
		return;
	if ( s_flFadeStart >= 0.0 )
	{
		const float flLeft = 1.0f - (float)( ( flNow - s_flFadeStart ) / 3.0 );
		if ( flLeft <= 0.0f )
		{
			enginesound->EmitAmbientSound( kMusic, 0.0f, PITCH_NORM, SND_STOP );
			s_bPlaying = false;
			s_flFadeStart = -1.0;
		}
		else
		{
			enginesound->EmitAmbientSound( kMusic, 0.7f * flLeft, PITCH_NORM, SND_CHANGE_VOL );
		}
		return;
	}
	if ( flNow >= s_flRestartAt )
		s_bPlaying = false;  // the start music has finished; it doesn't loop
}

// TF2's MvM upgrade screen (opened by Minecraft's enchanting table).
static CHudUpgradePanel *UpgradePanel()
{
	CHudUpgradePanel *pPanel = GET_HUDELEMENT( CHudUpgradePanel );
	return pPanel && pPanel->IsVisible() ? pPanel : NULL;
}

// A TF2 pop-up dialog is showing (yes/no questions, message boxes, item and trade notices...).
// TF2 can open these by itself, also in the middle of play (the one-time "HUD 3D character"
// question on first spawn): with nobody able to click them, testers got stuck (Alex, 2026-10-12).
// Any visible dialog of TF2's client counts as an open menu, so Minecraft frees the mouse for it.
static bool DialogOpen()
{
	vgui::ISurface *pSurface = vgui::surface();
	if ( !pSurface || !vgui::ipanel() )
		return false;
	static vgui::VPANEL s_LastDialog = 0;
	vgui::VPANEL found = 0;
	const char *pszName = "";
	for ( int i = 0; i < pSurface->GetPopupCount() && !found; ++i )
	{
		vgui::VPANEL vp = pSurface->GetPopup( i );
		if ( !vp || !vgui::ipanel()->IsFullyVisible( vp ) || !vgui::ipanel()->IsMouseInputEnabled( vp ) )
			continue;
		vgui::Panel *pPanel = vgui::ipanel()->GetPanel( vp, "ClientDLL" );
		if ( pPanel && ( dynamic_cast< CConfirmDialog * >( pPanel ) || dynamic_cast< vgui::Frame * >( pPanel ) ) )
		{
			found = vp;
			pszName = pPanel->GetName();
		}
	}
	if ( found != s_LastDialog )
	{
		s_LastDialog = found;
		if ( found )
			Msg( "FortCraft: TF2 dialog '%s' is showing; it takes the mouse\n", pszName );
	}
	return found != 0;
}

// A TF2 menu that takes the mouse is open.
static bool UiOpen()
{
	return ( enginevgui && enginevgui->IsGameUIVisible() ) || ( s_bUserMenu && ViewportMenuOpen() ) || UpgradePanel()
		|| DialogOpen();
}

bool FortCraft_UiOpen()
{
	return s_bLinked && UiOpen();
}

// Minecraft's key (USB HID / SDL scancode) to TF2's.
static ButtonCode_t KeyFromScancode( int sc )
{
	if ( sc >= 4 && sc <= 29 )
		return (ButtonCode_t)( KEY_A + ( sc - 4 ) );
	if ( sc >= 30 && sc <= 38 )
		return (ButtonCode_t)( KEY_1 + ( sc - 30 ) );
	if ( sc >= 58 && sc <= 69 )
		return (ButtonCode_t)( KEY_F1 + ( sc - 58 ) );
	if ( sc >= 89 && sc <= 97 )
		return (ButtonCode_t)( KEY_PAD_1 + ( sc - 89 ) );
	switch ( sc )
	{
	case 39: return KEY_0;
	case 40: return KEY_ENTER;
	case 41: return KEY_ESCAPE;
	case 42: return KEY_BACKSPACE;
	case 43: return KEY_TAB;
	case 44: return KEY_SPACE;
	case 45: return KEY_MINUS;
	case 46: return KEY_EQUAL;
	case 47: return KEY_LBRACKET;
	case 48: return KEY_RBRACKET;
	case 49: return KEY_BACKSLASH;
	case 51: return KEY_SEMICOLON;
	case 52: return KEY_APOSTROPHE;
	case 53: return KEY_BACKQUOTE;
	case 54: return KEY_COMMA;
	case 55: return KEY_PERIOD;
	case 56: return KEY_SLASH;
	case 57: return KEY_CAPSLOCK;
	case 73: return KEY_INSERT;
	case 74: return KEY_HOME;
	case 75: return KEY_PAGEUP;
	case 76: return KEY_DELETE;
	case 77: return KEY_END;
	case 78: return KEY_PAGEDOWN;
	case 79: return KEY_RIGHT;
	case 80: return KEY_LEFT;
	case 81: return KEY_DOWN;
	case 82: return KEY_UP;
	case 84: return KEY_PAD_DIVIDE;
	case 85: return KEY_PAD_MULTIPLY;
	case 86: return KEY_PAD_MINUS;
	case 87: return KEY_PAD_PLUS;
	case 88: return KEY_PAD_ENTER;
	case 98: return KEY_PAD_0;
	case 99: return KEY_PAD_DECIMAL;
	case 224: return KEY_LCONTROL;
	case 225: return KEY_LSHIFT;
	case 226: return KEY_LALT;
	case 228: return KEY_RCONTROL;
	case 229: return KEY_RSHIFT;
	case 230: return KEY_RALT;
	}
	return BUTTON_CODE_NONE;
}

static vgui::MouseCode MouseFromCode( int code )
{
	return code == 1 ? MOUSE_RIGHT : code == 2 ? MOUSE_MIDDLE : MOUSE_LEFT;
}

static float s_flMenuCursorX = -1.0f, s_flMenuCursorY = -1.0f;

static void MoveCursor( float x, float y )
{
	// TF2's hidden window has no real cursor input; keep the last Minecraft position so the
	// menu cursor can be restored every frame, including while the mouse is held still.
	s_flMenuCursorX = x;
	s_flMenuCursorY = y;
	int px = clamp( (int)( x * ScreenWidth() ), 0, ScreenWidth() - 1 );
	int py = clamp( (int)( y * ScreenHeight() ), 0, ScreenHeight() - 1 );
	g_InputInternal->UpdateCursorPosInternal( px, py );
	g_InputInternal->InternalCursorMoved( px, py );
}

// VGUI click handlers query the real surface cursor. Its hidden TF2 window has
// a different origin from Minecraft, so point it at the clicked TF2 control
// only for the duration of the event, then put the Windows cursor back.
static void MenuMouseButton( const proto::UiEvent &ev, bool pressed )
{
	MoveCursor( ev.x, ev.y );
	const int px = clamp( (int)( ev.x * ScreenWidth() ), 0, ScreenWidth() - 1 );
	const int py = clamp( (int)( ev.y * ScreenHeight() ), 0, ScreenHeight() - 1 );
	int savedX = 0, savedY = 0;
	const bool restore = FortCraftPlat_GetCursor( &savedX, &savedY );
	g_InputInternal->SetCursorPos( px, py );
	g_InputInternal->UpdateCursorPosInternal( px, py );
	g_InputInternal->InternalCursorMoved( px, py );
	if ( pressed )
	{
		int actualX = 0, actualY = 0;
		g_InputInternal->GetCursorPos( actualX, actualY );
		Msg( "FortCraft menu click: sent %.3f,%.3f -> TF2 cursor %d,%d of %d,%d\n",
			ev.x, ev.y, actualX, actualY, ScreenWidth(), ScreenHeight() );
		g_InputInternal->SetMouseCodeState( MouseFromCode( ev.code ), vgui::BUTTON_PRESSED );
		g_InputInternal->InternalMousePressed( MouseFromCode( ev.code ) );
	}
	else
	{
		g_InputInternal->SetMouseCodeState( MouseFromCode( ev.code ), vgui::BUTTON_RELEASED );
		g_InputInternal->InternalMouseReleased( MouseFromCode( ev.code ) );
	}
	if ( restore )
		FortCraftPlat_SetCursor( savedX, savedY );
}

static void CloseAllMenus()
{
	if ( enginevgui && enginevgui->IsGameUIVisible() )
		engine->ClientCmd_Unrestricted( "gameui_hide\n" );
	if ( CHudUpgradePanel *pUpgrades = UpgradePanel() )
		pUpgrades->OnCommand( "close" );  // keeps what was bought, like its own close button
	if ( gViewPortInterface )
	{
		IViewPortPanel *pPanel = gViewPortInterface->GetActivePanel();
		if ( pPanel && V_strcmp( pPanel->GetName(), PANEL_SCOREBOARD ) != 0 )
			gViewPortInterface->ShowPanel( pPanel, false );
	}
	s_bUserMenu = false;
}

static void RunMenuCommand( const char *pszCommand )
{
	s_bUserMenu = true;
	s_flUserMenuAt = Plat_FloatTime();
	engine->ClientCmd_Unrestricted( pszCommand );
}

// Mouse and keys while a TF2 menu is open: straight into TF2's UI.
// TF2's console text box ("ConsoleEntry" inside GameUI), found by name; 0 if not showing.
static vgui::VPANEL FindPanelByName( vgui::VPANEL parent, const char *pszName, int depth )
{
	if ( !parent || depth > 12 )
		return 0;
	for ( int i = 0; i < vgui::ipanel()->GetChildCount( parent ); ++i )
	{
		vgui::VPANEL child = vgui::ipanel()->GetChild( parent, i );
		if ( !child || !vgui::ipanel()->IsVisible( child ) )
			continue;
		if ( !V_strcmp( vgui::ipanel()->GetName( child ), pszName ) )
			return child;
		if ( vgui::VPANEL found = FindPanelByName( child, pszName, depth + 1 ) )
			return found;
	}
	return 0;
}

static vgui::VPANEL ConsoleEntry()
{
	if ( !engine->Con_IsVisible() || !enginevgui )
		return 0;
	static vgui::VPANEL s_entry;
	if ( s_entry && vgui::ipanel()->IsVisible( s_entry ) && !V_strcmp( vgui::ipanel()->GetName( s_entry ), "ConsoleEntry" ) )
		return s_entry;
	s_entry = FindPanelByName( enginevgui->GetPanel( PANEL_ROOT ), "ConsoleEntry", 0 );
	return s_entry;
}

// Typing into the console. TF2's window is hidden and never has the keyboard, so TF2's console
// box never got the letters (Alex, 2026-10-10: "people can't type in console"; sending them to
// the box directly didn't help either). So while the console shows, FortCraft keeps the line
// being typed itself: letters add to it, Backspace removes, Up brings back the last command,
// Enter runs it as if typed in TF2's console. The console box shows the line as it's typed.
static char s_szConsoleLine[ 256 ];
static char s_szConsoleLast[ 256 ];

static void ShowConsoleLine()
{
	if ( vgui::VPANEL entry = ConsoleEntry() )
		vgui::ipanel()->SendMessage( entry, new KeyValues( "SetText", "text", s_szConsoleLine ), entry );
}

static bool ConsoleTyping( const proto::UiEvent &ev )
{
	if ( !engine->Con_IsVisible() )
	{
		s_szConsoleLine[ 0 ] = 0;
		return false;
	}
	static int s_nLogged;
	if ( s_nLogged < 3 )
	{
		++s_nLogged;
		vgui::VPANEL focus = vgui::input()->GetFocus();
		Msg( "FortCraft console: event %d code %d (console box %s, focus %s)\n", ev.type, ev.code,
			ConsoleEntry() ? "found" : "not found", focus ? vgui::ipanel()->GetName( focus ) : "none" );
	}
	const int len = V_strlen( s_szConsoleLine );
	if ( ev.type == proto::kUiEvChar )
	{
		if ( ev.code >= 32 && ev.code < 127 && len < (int)sizeof( s_szConsoleLine ) - 1 )
		{
			s_szConsoleLine[ len ] = (char)ev.code;
			s_szConsoleLine[ len + 1 ] = 0;
			ShowConsoleLine();
		}
		return true;
	}
	if ( ev.type == proto::kUiEvKeyDown )
	{
		ButtonCode_t key = KeyFromScancode( ev.code );
		if ( key == KEY_ENTER || key == KEY_PAD_ENTER )
		{
			if ( s_szConsoleLine[ 0 ] )
			{
				Msg( "] %s\n", s_szConsoleLine );
				engine->ClientCmd_Unrestricted( VarArgs( "%s\n", s_szConsoleLine ) );
				V_strncpy( s_szConsoleLast, s_szConsoleLine, sizeof( s_szConsoleLast ) );
				s_szConsoleLine[ 0 ] = 0;
				ShowConsoleLine();
			}
		}
		else if ( key == KEY_BACKSPACE && len > 0 )
		{
			s_szConsoleLine[ len - 1 ] = 0;
			ShowConsoleLine();
		}
		else if ( key == KEY_UP && s_szConsoleLast[ 0 ] )
		{
			V_strncpy( s_szConsoleLine, s_szConsoleLast, sizeof( s_szConsoleLine ) );
			ShowConsoleLine();
		}
		return true;
	}
	return ev.type == proto::kUiEvKeyUp;
}

static void UiInputEvent( const proto::UiEvent &ev )
{
	if ( !g_InputInternal )
		return;
	if ( ( ev.type == proto::kUiEvChar || ev.type == proto::kUiEvKeyDown || ev.type == proto::kUiEvKeyUp ) && ConsoleTyping( ev ) )
		return;
	switch ( ev.type )
	{
	case proto::kUiEvMouseMove:
		MoveCursor( ev.x, ev.y );
		break;
	case proto::kUiEvMouseDown:
		MenuMouseButton( ev, true );
		break;
	case proto::kUiEvMouseUp:
		MenuMouseButton( ev, false );
		break;
	case proto::kUiEvWheel:
		g_InputInternal->InternalMouseWheeled( ev.code );
		break;
	case proto::kUiEvKeyDown:
	{
		ButtonCode_t key = KeyFromScancode( ev.code );
		if ( key != BUTTON_CODE_NONE )
		{
			g_InputInternal->SetKeyCodeState( key, true );
			g_InputInternal->InternalKeyCodePressed( key );
			g_InputInternal->InternalKeyCodeTyped( key );
		}
		break;
	}
	case proto::kUiEvKeyUp:
	{
		ButtonCode_t key = KeyFromScancode( ev.code );
		if ( key != BUTTON_CODE_NONE )
		{
			g_InputInternal->SetKeyCodeState( key, false );
			g_InputInternal->InternalKeyCodeReleased( key );
		}
		break;
	}
	case proto::kUiEvChar:
		if ( ev.code > 0 && ev.code < 0x10000 )
			g_InputInternal->InternalKeyTyped( (wchar_t)ev.code );
		break;
	}
}

static void UiCommand( C_TFPlayer *pPlayer, uint32_t cmd );

// Everything Minecraft passed on since last frame: menu keys, and the mouse and keyboard while
// a TF2 menu is open.
static void PollUiEvents( C_TFPlayer *pPlayer )
{
	// A requested class/team menu that never appeared, or was closed in TF2: back to normal.
	if ( s_bUserMenu && !ViewportMenuOpen() && Plat_FloatTime() - s_flUserMenuAt > 1.0 )
		s_bUserMenu = false;

	const proto::UiEvents &ui = At< proto::UiEvents >( proto::kOffUiEvents );
	static uint32_t s_nSeen = 0xFFFFFFFF;
	uint32_t count = *(volatile const uint32_t *)&ui.count;
	if ( s_nSeen == 0xFFFFFFFF || count - s_nSeen > proto::kMaxUiEvents )
		s_nSeen = count;  // first look, or too far behind: start from now
	while ( s_nSeen != count )
	{
		const proto::UiEvent ev = ui.ring[ s_nSeen % proto::kMaxUiEvents ];
		++s_nSeen;
		if ( ev.type == proto::kUiEvCommand )
			UiCommand( pPlayer, (uint32_t)ev.code );
		else
			UiInputEvent( ev );
	}
	if ( UiOpen() && s_flMenuCursorX >= 0.0f && g_InputInternal )
		MoveCursor( s_flMenuCursorX, s_flMenuCursorY );
	UpgradeMusic( UpgradePanel() != NULL );
	RaidMusic();

	// Report a change of menu state once, so the log shows what happened.
	static bool s_bWasOpen;
	bool bOpen = UiOpen();
	if ( bOpen != s_bWasOpen )
	{
		s_bWasOpen = bOpen;
		Msg( "FortCraft: TF2 menu %s\n", bOpen ? "open: Minecraft forwards mouse and keys" : "closed" );
	}
}

// Keys Minecraft passes on for TF2's own menus. The taunt menu is the real one, with the taunts
// equipped in your TF2 loadout; G opens it, G again does the weapon taunt, 1-8 pick, and G or Q
// while taunting stops the taunt.
static void UiCommand( C_TFPlayer *pPlayer, uint32_t cmd )
{
	if ( cmd >= proto::kUiVoiceMenu1 && cmd < proto::kUiVoiceMenu1 + 3 )
	{
		engine->ClientCmd_Unrestricted( VarArgs( "voice_menu_%u\n", cmd - proto::kUiVoiceMenu1 + 1 ) );
		Msg( "FortCraft: TF2 voice menu %u\n", cmd - proto::kUiVoiceMenu1 + 1 );
		return;
	}
	// Q with a PDA out (Engineer build/destroy, Spy disguise kit): put it away, back to the last
	// weapon. Their number keys pick buildings or disguises, so there was no way back but a left
	// click (GitHub issue 13; the Spy's kit too, Alex 2026-10-13).
	if ( cmd == proto::kUiCancel && pPlayer && IsPda( pPlayer->GetActiveWeapon() ) )
	{
		engine->ClientCmd( "lastinv\n" );
		Msg( "FortCraft: Q put the PDA away\n" );
		return;
	}
	if ( cmd >= proto::kUiVoiceSelect1 && cmd < proto::kUiVoiceSelect1 + 9 )
	{
		engine->ClientCmd_Unrestricted( VarArgs( "menuselect %u\n", cmd - proto::kUiVoiceSelect1 + 1 ) );
		Msg( "FortCraft: TF2 voice selection %u\n", cmd - proto::kUiVoiceSelect1 + 1 );
		return;
	}
	if ( cmd == proto::kUiVoiceCancel )
	{
		engine->ClientCmd_Unrestricted( "menuselect 0\n" );
		Msg( "FortCraft: TF2 voice menu cancelled\n" );
		return;
	}
	if ( cmd >= proto::kUiBuildSlot1 && cmd < proto::kUiBuildSlot1 + 4 && pPlayer )
	{
		C_TFWeaponBase *pActive = dynamic_cast< C_TFWeaponBase * >( pPlayer->GetActiveWeapon() );
		const int id = pActive ? pActive->GetWeaponID() : TF_WEAPON_NONE;
		if ( id == TF_WEAPON_PDA_ENGINEER_BUILD || id == TF_WEAPON_PDA_ENGINEER_DESTROY )
		{
			static const int s_Building[ 4 ][ 2 ] = { { OBJ_SENTRYGUN, 0 }, { OBJ_DISPENSER, 0 },
				{ OBJ_TELEPORTER, MODE_TELEPORTER_ENTRANCE }, { OBJ_TELEPORTER, MODE_TELEPORTER_EXIT } };
			const int slot = cmd - proto::kUiBuildSlot1;
			const bool build = id == TF_WEAPON_PDA_ENGINEER_BUILD;
			engine->ClientCmd( VarArgs( "%s %d %d\n", build ? "build" : "destroy",
				s_Building[ slot ][ 0 ], s_Building[ slot ][ 1 ] ) );
			Msg( "FortCraft: Engineer %s menu fresh key %d\n", build ? "build" : "destroy", slot + 1 );
		}
		return;
	}
	switch ( cmd )
	{
	case proto::kUiClassMenu:
		RunMenuCommand( "changeclass\n" );
		Msg( "FortCraft: opening TF2's class menu\n" );
		return;
	case proto::kUiTeamMenu:
		RunMenuCommand( "changeteam\n" );
		Msg( "FortCraft: opening TF2's team menu\n" );
		return;
	case proto::kUiLoadout:
		engine->ClientCmd_Unrestricted( "open_charinfo_direct\n" );
		Msg( "FortCraft: opening TF2's loadout\n" );
		return;
	case proto::kUiScoresDown:
		engine->ClientCmd_Unrestricted( "+showscores\n" );
		return;
	case proto::kUiScoresUp:
		engine->ClientCmd_Unrestricted( "-showscores\n" );
		return;
	case proto::kUiMainMenu:
		if ( enginevgui && enginevgui->IsGameUIVisible() )
			engine->ClientCmd_Unrestricted( "gameui_hide\n" );
		else
			engine->ClientCmd_Unrestricted( "gameui_activate\n" );
		Msg( "FortCraft: toggling TF2's main menu\n" );
		return;
	case proto::kUiCloseAll:
		CloseAllMenus();
		Msg( "FortCraft: closing TF2's menus\n" );
		return;
	case proto::kUiBackpack:
		engine->ClientCmd_Unrestricted( "open_charinfo_backpack\n" );
		s_flBackpackLastPageUntil = Plat_FloatTime() + 2.0;  // turn to the last page once it shows
		Msg( "FortCraft: opening TF2's backpack at the Minecraft items\n" );
		return;
	case proto::kUiConsole:
		// The console shows inside TF2's main menu layer (GameUI), so Minecraft's pass-through
		// screen forwards keys and typing to it like any TF2 menu. Closing it also closes that layer.
		if ( engine->Con_IsVisible() )
		{
			engine->ClientCmd_Unrestricted( "hideconsole; gameui_hide\n" );
			Msg( "FortCraft: closing TF2's console\n" );
		}
		else
		{
			engine->ClientCmd_Unrestricted( "con_enable 1; showconsole\n" );
			Msg( "FortCraft: opening TF2's console\n" );
		}
		return;
	}

	// Eureka Effect's teleport menu (opened with Reload): 1 = home (Minecraft's bed or spawn), 2 =
	// teleporter exit, Q = cancel. Minecraft sends these as the taunt-menu keys while the menu is
	// open (camera flag kCameraEurekaMenu); the menu's own key handling picks and closes it.
	if ( EurekaMenuOpen() && ( cmd == proto::kUiSlot1 || cmd == proto::kUiSlot1 + 1 || cmd == proto::kUiCancel ) )
	{
		CHudEurekaEffectTeleportMenu *pMenu = GET_HUDELEMENT( CHudEurekaEffectTeleportMenu );
		if ( cmd == proto::kUiSlot1 || cmd == proto::kUiSlot1 + 1 )
		{
			pMenu->HudElementKeyInput( 1, cmd == proto::kUiSlot1 ? KEY_1 : KEY_2, NULL );
			Msg( "FortCraft: Eureka Effect teleport to %s\n", cmd == proto::kUiSlot1 ? "spawn" : "teleporter exit" );
		}
		else
		{
			pMenu->FortCraft_Cancel();
			Msg( "FortCraft: Eureka Effect teleport cancelled\n" );
		}
		return;
	}

	bool bMenu = pPlayer->ShouldShowHudMenuTauntSelection();
	bool bTaunting = pPlayer->m_Shared.InCond( TF_COND_TAUNTING );
	if ( cmd == proto::kUiTauntKey )
	{
		if ( bMenu )
		{
			pPlayer->SetShowHudMenuTauntSelection( false );
			engine->ClientCmd_Unrestricted( "taunt 0\n" );  // the menu's "g for weapon taunt"
		}
		else if ( bTaunting )
		{
			engine->ClientCmd_Unrestricted( "stop_taunt\n" );
		}
		else
		{
			engine->ClientCmd_Unrestricted( "+taunt\n" );  // opens the menu (or weapon taunt if none equipped)
			engine->ClientCmd_Unrestricted( "-taunt\n" );
		}
	}
	else if ( cmd >= proto::kUiSlot1 && cmd < proto::kUiSlot1 + 8 && bMenu )
	{
		pPlayer->SetShowHudMenuTauntSelection( false );
		engine->ClientCmd_Unrestricted( VarArgs( "taunt %u\n", cmd - proto::kUiSlot1 + 1 ) );
	}
	else if ( cmd == proto::kUiCancel )
	{
		if ( bMenu )
			pPlayer->SetShowHudMenuTauntSelection( false );
		else if ( bTaunting )
			engine->ClientCmd_Unrestricted( "stop_taunt\n" );
	}
	Msg( "FortCraft: key command %u (menu %d, taunting %d)\n", cmd, bMenu ? 1 : 0, bTaunting ? 1 : 0 );
}

float FortCraft_WorldLight()
{
	if ( !s_bLinked || !s_pShm )
		return 1.0f;
	const float flLight = At< proto::HostDisplay >( proto::kOffHostDisplay ).light;
	return ( flLight > 0.0f && flLight <= 1.0f ) ? flLight : 1.0f;
}

bool FortCraft_IsLinked()
{
	return s_bLinked;
}

static void NotePairPosition( const Vector &origin );

void FortCraft_OverrideView( float &flFov, float &flAspectRatio, const Vector &origin, const QAngle &angles )
{
	if ( !s_bLinked )
		return;
	WriteCamera( origin, angles );
	NotePairPosition( origin );
	const proto::HostDisplay &d = At< proto::HostDisplay >( proto::kOffHostDisplay );
	if ( d.fovY <= 1.0f || d.fovY >= 179.0f || d.aspect <= 0.1f )
		return;
	// Source's fov is horizontal; Minecraft's is vertical. Same screen shape as Minecraft's
	// window, because Minecraft stretches TF2's picture to fill it.
	flAspectRatio = d.aspect;
	flFov = RAD2DEG( 2.0f * atanf( tanf( DEG2RAD( d.fovY ) * 0.5f ) * d.aspect ) );
}

// Use Minecraft's newest look direction for this frame's view, not the one from the last game
// tick (TF2 only builds movement commands at its tick rate, so the view lagged on fast turns).
static void ApplyLatestLook( C_TFPlayer *pPlayer )
{
	const proto::InputState &in = At< proto::InputState >( kOffInput );
	uint32_t seq = *(volatile const uint32_t *)&in.seq;
	if ( seq & 1 )
		return;
	QAngle ang( in.pitch, AngleNormalize( -in.yaw - 90.0f ), 0.0f );
	const float flYaw = in.yaw, flPitch = in.pitch;
	if ( *(volatile const uint32_t *)&in.seq != seq )
		return;
	engine->SetViewAngles( ang );
	s_LastLookPose.yaw = flYaw;
	s_LastLookPose.pitch = flPitch;
	s_LastLookPose.timeUs = FortCraftPlat_NowUs();  // this frame's start, for Minecraft's pacing
}

// ---- Overlay ---------------------------------------------------------------------------------
// TF2's frame with its world blanked out leaves only the weapon and HUD. To get real
// transparency, the blank background alternates between black and white on two frames in a
// row: a solid pixel looks the same on both, a see-through one shows the background, and the
// difference gives each pixel's transparency (no colour key, so no pink edges).
//
// Speed (measured 2026-10-04 at 2560x1440): each ReadPixels makes TF2 wait ~12 ms, and the
// per-pixel combine took ~4.5 ms. So: one read per frame (no extra redraw), in the GPU's own
// pixel order (no conversion), the combine on a worker thread, and captures rate-limited so
// TF2 keeps most of its time for itself.

static int s_nOverlayFps = 0;  // Minecraft's frame rate limit, once known
static double s_flLastPair;
static double s_flReadMsAvg = 5.0;  // recent average cost of one read
static int s_nPass = -1;             // this frame: -1 no capture, 0 black, 1 white
static int s_nCaptureW, s_nCaptureH;
static const double kCaptureTimeBudget = 0.5;  // Alex: as smooth as possible

// Buffers: the two reads of the pair being captured, and the pair the worker is combining.
static CUtlMemory< unsigned char > s_Capture[ 2 ];
static CUtlMemory< unsigned char > s_Work[ 2 ];
static int s_nWorkW, s_nWorkH;
static CThreadEvent s_WorkReady;
static volatile bool s_bWorkerBusy;
static ThreadHandle_t s_hWorker;

// Perf counters, logged every 5 s.
static double s_flSumRead, s_flSumCombine, s_flLogStart;
static int s_nReads, s_nPairs;
static uint64_t s_nFrameAtLog;
static void LogPerf( int width, int height, const char *pszPath );

// Launch switches: -fortcraft_no_overlay (no weapon/HUD copy at all, to tell overlay cost from
// TF2's own), -fortcraft_cpu_overlay (skip the GPU path, use the read-back path).
static bool s_bNoOverlay;
static bool s_bForceCpu;

// GPU path available? Tries once to set it up; logs why not if it can't.
static bool GpuOverlay()
{
	static int s_nState = 0;  // 0 untried, 1 trying, 2 ready, 3 not available
	if ( s_nState == 2 && FortCraftGpu_Broken() )
	{
		s_nState = 3;
		Msg( "FortCraft: GPU overlay stopped (%s); using the slower read-back path\n", FortCraftGpu_Error() );
		At< proto::OverlayGpu >( proto::kOffOverlayGpu ).valid = 0;
	}
	if ( s_nState == 2 && ( At< proto::HostDisplay >( proto::kOffHostDisplay ).flags & proto::kHostNoGpuOverlay ) )
	{
		s_nState = 3;
		Msg( "FortCraft: Minecraft can't open the GPU overlay; using the slower read-back path\n" );
		At< proto::OverlayGpu >( proto::kOffOverlayGpu ).valid = 0;
	}
	if ( s_nState >= 2 )
		return s_nState == 2;
	// On by default since 2026-10-05: TF2's device is now found directly in shaderapidx9.dll
	// (hooking Direct3D's tables never caught it). -fortcraft_cpu_overlay forces the read-back path.
	if ( s_bForceCpu )
	{
		s_nState = 3;
		Msg( "FortCraft: GPU overlay off; using the read-back path\n" );
		return false;
	}
	if ( FortCraftGpu_Init( NULL ) )
	{
		s_nState = 2;
		Msg( "FortCraft: GPU overlay ready (hooked %s)\n", FortCraftGpu_HookInfo() );
		return true;
	}
	static double s_flGiveUpAt;
	if ( s_nState == 0 )
	{
		s_nState = 1;
		s_flGiveUpAt = Plat_FloatTime() + 5.0;
	}
	if ( strcmp( FortCraftGpu_Error(), "waiting for TF2's device" ) != 0 || Plat_FloatTime() > s_flGiveUpAt )
	{
		s_nState = 3;
		FortCraftGpu_GiveUp();
		Msg( "FortCraft: GPU overlay not available (%s; hooked %s); using the slower read-back path\n", FortCraftGpu_Error(), FortCraftGpu_HookInfo() );
		if ( CommandLine()->CheckParm( "-fortcraft_gpu_survey" ) )
			FortCraftGpu_Survey( []( const char *pszLine ) { Msg( "%s", pszLine ); } );
	}
	return false;
}

static void CombinePair( const unsigned char *pBlack, const unsigned char *pWhite, int width, int height )
{
	proto::OverlayHeader &oh = At< proto::OverlayHeader >( proto::kOffOverlay );
	uint32_t back = oh.front ^ 1;
	uint32_t *pDest32 = (uint32_t *)( s_pShm + proto::kOffOverlayPixels + back * proto::kOverlaySlotBytes );

	static unsigned int s_Inverse[ 256 ];  // (255 << 16) / alpha
	if ( !s_Inverse[ 1 ] )
	{
		for ( int a = 1; a < 256; ++a )
			s_Inverse[ a ] = ( 255u << 16 ) / a;
	}

	// Input is the GPU's order (B, G, R, unused); output is R, G, B, A for Minecraft, in
	// premultiplied alpha: the colour over black is already colour * alpha. (Straight alpha,
	// dividing by alpha, turned glowing additive effects white and faint.)
	// over black = colour * alpha; over white = colour * alpha + (1 - alpha).
	const uint32_t *pB = (const uint32_t *)pBlack, *pW = (const uint32_t *)pWhite;
	const int nPixels = width * height;
	for ( int i = 0; i < nPixels; ++i )
	{
		uint32_t b = pB[ i ] & 0x00FFFFFFu, w = pW[ i ] & 0x00FFFFFFu;
		if ( b == 0 && w == 0x00FFFFFFu )
		{
			pDest32[ i ] = 0;  // nothing drawn here
			continue;
		}
		int bb = b & 0xFF, bg = ( b >> 8 ) & 0xFF, br = ( b >> 16 ) & 0xFF;
		if ( b == w )
		{
			pDest32[ i ] = br | ( bg << 8 ) | ( bb << 16 ) | 0xFF000000u;  // solid
			continue;
		}
		int wb = w & 0xFF, wg = ( w >> 8 ) & 0xFF, wr = ( w >> 16 ) & 0xFF;
		int alpha = clamp( 255 - MAX( wr - br, MAX( wg - bg, wb - bb ) ), 0, 255 );
		if ( alpha == 0 )
		{
			pDest32[ i ] = 0;
			continue;
		}
		pDest32[ i ] = br | ( bg << 8 ) | ( bb << 16 ) | ( (uint32_t)alpha << 24 );
	}

	oh.width = width;
	oh.height = height;
	At< proto::OverlayPose >( proto::kOffOverlayPose + back * sizeof( proto::OverlayPose ) ) = s_WorkPose;
	*(volatile uint32_t *)&oh.front = back;
	*(volatile uint32_t *)&oh.seq = oh.seq + 1;
}

static uintp WorkerMain( void * )
{
	for ( ;; )
	{
		s_WorkReady.Wait();
		double flStart = Plat_FloatTime();
		CombinePair( s_Work[ 0 ].Base(), s_Work[ 1 ].Base(), s_nWorkW, s_nWorkH );
		s_flSumCombine += Plat_FloatTime() - flStart;
		s_bWorkerBusy = false;
	}
	return 0;
}

// Minecraft's solid blocks and mobs drawn into TF2's depth buffer only
// (invisible), so rockets and effects drawn afterward are hidden behind them.
// Walk-through plants are omitted: their box-only depth masks hid whole mercs
// and the narrow substitute still cut conspicuous strips through them.
// Blocks in front of far robots (protocol RobotOccluders, v64), in TF2 units.
static void ReadRobotOccluders( CUtlVector< FortCraftBox > &out )
{
	const proto::RobotOccluders &src = At< proto::RobotOccluders >( proto::kOffRobotOccluders );
	const uint32_t seq = *(volatile const uint32_t *)&src.seq;
	if ( seq & 1 )
		return;  // mid-write: keep last frame's
	CUtlVector< FortCraftBox > boxes;
	const uint32_t n = MIN( src.count, proto::kMaxRobotOccluders );
	for ( uint32_t i = 0; i < n; ++i )
	{
		const proto::BlockBox &b = src.boxes[ i ];
		FortCraftBox &o = boxes[ boxes.AddToTail() ];
		o.mins.Init( ( b.minX - 0.5f ) * UNITS_PER_BLOCK + s_vecOrigin.x, -( b.maxZ - 0.5f ) * UNITS_PER_BLOCK + s_vecOrigin.y,
			( b.minY - (float)MC_FLOOR_Y ) * UNITS_PER_BLOCK + s_vecOrigin.z );
		o.maxs.Init( ( b.maxX - 0.5f ) * UNITS_PER_BLOCK + s_vecOrigin.x, -( b.minZ - 0.5f ) * UNITS_PER_BLOCK + s_vecOrigin.y,
			( b.maxY - (float)MC_FLOOR_Y ) * UNITS_PER_BLOCK + s_vecOrigin.z );
	}
	if ( *(volatile const uint32_t *)&src.seq != seq )
		return;
	out.Swap( boxes );
}

static void DrawBlockDepth()
{
	static CUtlVector< FortCraftBox > s_Occluders;
	ReadRobotOccluders( s_Occluders );
	int counts[ 4 ] = { 0, 0, 0, s_Occluders.Count() };
	const FortCraftBox *lists[ 4 ] = {
		FortCraft_Boxes( &counts[ 0 ] ), FortCraft_VisualBoxes( &counts[ 1 ] ), FortCraft_MobBoxes( &counts[ 2 ] ), s_Occluders.Base()
	};
	// Mobs TF2 draws as robots stay out of the depth layer: the robot would be hidden inside its box.
	int nFlags = 0;
	const unsigned char *pMobFlags = FortCraft_MobFlags( &nFlags );
	static CUtlVector< FortCraftBox > s_DepthMobs;
	s_DepthMobs.RemoveAll();
	for ( int i = 0; i < counts[ 2 ]; ++i )
	{
		if ( i >= nFlags || !( pMobFlags[ i ] & proto::kMobRobot ) )
			s_DepthMobs.AddToTail( lists[ 2 ][ i ] );
	}
	lists[ 2 ] = s_DepthMobs.Base();
	counts[ 2 ] = s_DepthMobs.Count();
	if ( !counts[ 0 ] && !counts[ 2 ] && !counts[ 3 ] )
		return;
	static CMaterialReference s_WriteZ;
	if ( !s_WriteZ.IsValid() )
		s_WriteZ.Init( "engine/writez", TEXTURE_GROUP_OTHER );

	CMatRenderContextPtr pRenderContext( materials );
	pRenderContext->Bind( s_WriteZ );
	const int kBoxesPerBatch = 500;  // 6 quads = 24 vertices, 36 indices each; a dynamic mesh takes at most 32768 indices
	for ( int list = 0; list < 4; ++list )
	{
		if ( list == 1 )
			continue;
		const FortCraftBox *pBoxes = lists[ list ];
		for ( int start = 0; start < counts[ list ]; start += kBoxesPerBatch )
		{
			int n = MIN( kBoxesPerBatch, counts[ list ] - start );
			IMesh *pMesh = pRenderContext->GetDynamicMesh();
			CMeshBuilder mb;
			mb.Begin( pMesh, MATERIAL_QUADS, n * 6 );
			for ( int i = start; i < start + n; ++i )
			{
				const Vector &a = pBoxes[ i ].mins, &b = pBoxes[ i ].maxs;
				Vector v[ 8 ] = {
					Vector( a.x, a.y, a.z ), Vector( b.x, a.y, a.z ), Vector( b.x, b.y, a.z ), Vector( a.x, b.y, a.z ),
					Vector( a.x, a.y, b.z ), Vector( b.x, a.y, b.z ), Vector( b.x, b.y, b.z ), Vector( a.x, b.y, b.z ),
				};
				static const int kFaces[ 6 ][ 4 ] = {
					{ 0, 3, 2, 1 }, { 4, 5, 6, 7 }, { 0, 1, 5, 4 }, { 2, 3, 7, 6 }, { 1, 2, 6, 5 }, { 3, 0, 4, 7 },
				};
				for ( int f = 0; f < 6; ++f )
				{
					for ( int k = 0; k < 4; ++k )
					{
						mb.Position3fv( v[ kFaces[ f ][ k ] ].Base() );
						mb.AdvanceVertex();
					}
				}
			}
			mb.End();
			pMesh->Draw();
		}
	}
	static double s_flNextDepthLog;
	if ( Plat_FloatTime() >= s_flNextDepthLog )
	{
		s_flNextDepthLog = Plat_FloatTime() + 5.0;
		PerfLog( "FortCraft depth: %d solid, %d visual skipped, %d mob boxes\n", counts[ 0 ], counts[ 1 ], counts[ 2 ] );
	}
}

// Soft round shadows on Minecraft's ground under TF2's players, buildings, projectiles and
// pickups (Alex, 2026-10-10: they looked like they floated). TF2's own shadows need TF2's map;
// these find the ground in Minecraft's blocks below each thing (blocks near the player only),
// fade with height, and are hidden behind blocks by the depth layer drawn just before.
static void DrawBlobShadows()
{
	static CMaterialReference s_Blob;
	if ( !s_Blob.IsValid() )
	{
		KeyValues *kv = new KeyValues( "UnlitGeneric" );
		kv->SetString( "$basetexture", "vgui/white" );
		kv->SetInt( "$vertexcolor", 1 );
		kv->SetInt( "$vertexalpha", 1 );
		kv->SetInt( "$translucent", 1 );
		s_Blob.Init( materials->CreateMaterial( "__fortcraft_blob_shadow", kv ) );
	}
	C_BasePlayer *pLocal = C_BasePlayer::GetLocalPlayer();
	const float flMaxHeight = 3.0f * UNITS_PER_BLOCK;
	CMatRenderContextPtr pRenderContext( materials );
	pRenderContext->Bind( s_Blob );
	int nDrawn = 0;
	for ( C_BaseEntity *pEnt = ClientEntityList().FirstBaseEntity(); pEnt && nDrawn < 256; pEnt = ClientEntityList().NextBaseEntity( pEnt ) )
	{
		if ( pEnt->IsDormant() || !pEnt->GetModel() || pEnt->IsEffectActive( EF_NODRAW ) || pEnt->GetMoveParent() )
			continue;
		if ( pEnt == pLocal && !( ::input && ::input->CAM_IsThirdPerson() ) )
			continue;  // your own merc isn't drawn in first person
		if ( pEnt->IsPlayer() ? !pEnt->IsAlive() : false )
			continue;
		if ( modelinfo->GetModelType( pEnt->GetModel() ) == mod_brush || pEnt->IsBaseCombatWeapon() )
			continue;
		const char *pszClass = pEnt->GetClientClass() ? pEnt->GetClientClass()->m_pNetworkName : "";
		if ( V_stristr( pszClass, "ViewModel" ) || V_stristr( pszClass, "Prop" ) || V_stristr( pszClass, "World" ) || V_stristr( pszClass, "Ragdoll" ) )
			continue;
		const Vector origin = pEnt->GetAbsOrigin();
		trace_t tr;
		FortCraft_ClearTrace( origin + Vector( 0, 0, 4 ), origin - Vector( 0, 0, flMaxHeight ), tr );
		FortCraft_ClipHullTrace( origin + Vector( 0, 0, 4 ), origin - Vector( 0, 0, flMaxHeight ), vec3_origin, vec3_origin, tr );
		if ( !tr.DidHit() || tr.plane.normal.z < 0.7f )
			continue;  // nothing below (or a wall): no shadow
		const float flHeight = MAX( 0.0f, origin.z - tr.endpos.z );
		const float flFade = 1.0f - flHeight / flMaxHeight;
		const float flRadius = clamp( pEnt->CollisionProp()->BoundingRadius2D() * 0.7f, 6.0f, 40.0f ) * ( 0.6f + 0.4f * flFade );
		const unsigned char alpha = (unsigned char)( 110.0f * flFade );
		const Vector centre = tr.endpos + Vector( 0, 0, 0.6f );
		const int kSegments = 16;
		IMesh *pMesh = pRenderContext->GetDynamicMesh();
		CMeshBuilder mb;
		mb.Begin( pMesh, MATERIAL_TRIANGLES, kSegments );
		for ( int i = 0; i < kSegments; ++i )
		{
			const float a0 = 2.0f * M_PI_F * i / kSegments, a1 = 2.0f * M_PI_F * ( i + 1 ) / kSegments;
			mb.Position3fv( centre.Base() );
			mb.Color4ub( 0, 0, 0, alpha );
			mb.TexCoord2f( 0, 0.5f, 0.5f );
			mb.AdvanceVertex();
			const Vector p1 = centre + Vector( cosf( a1 ) * flRadius, sinf( a1 ) * flRadius, 0 );
			mb.Position3fv( p1.Base() );
			mb.Color4ub( 0, 0, 0, 0 );
			mb.TexCoord2f( 0, 0.5f, 0.5f );
			mb.AdvanceVertex();
			const Vector p0 = centre + Vector( cosf( a0 ) * flRadius, sinf( a0 ) * flRadius, 0 );
			mb.Position3fv( p0.Base() );
			mb.Color4ub( 0, 0, 0, 0 );
			mb.TexCoord2f( 0, 0.5f, 0.5f );
			mb.AdvanceVertex();
		}
		mb.End();
		pMesh->Draw();
		++nDrawn;
	}
}

// Each draw of the view gets a serial number, so the blank-and-depth step runs once per draw.
static int s_nRenderSerial, s_nClearedSerial = -1;

// The eye position the capture pair is drawn from (first draw), sent with the overlay so
// Minecraft draws its world from the same spot: otherwise strafing made TF2's picture slide.
static void NotePairPosition( const Vector &origin )
{
	if ( s_nPass != 0 || !s_bHaveOrigin )
		return;
	ToMinecraft( origin, s_PairPose.x, s_PairPose.y, s_PairPose.z );
	s_PairPose.hasPosition = 1;
}

int FortCraft_OverlayDraws()
{
	// Called once per frame (View_Render) before drawing. A capture pair is drawn as two draws
	// of the same frame, over black and then over white, from the same game state and look:
	// nothing moves between the halves, so nothing leaves a ghost. Returns 1 or 2 draws.
	if ( !s_bLinked || s_bNoOverlay )
		return 1;
	bool bCapture = false;
	if ( GpuOverlay() )
	{
		bCapture = true;  // GPU path: no read-back stall, so every frame makes an overlay frame
	}
	else
	{
		double flNow = Plat_FloatTime();
		double flMaxRate = s_nOverlayFps > 0 ? s_nOverlayFps : 60.0;
		double flBudgetRate = kCaptureTimeBudget * 1000.0 / MAX( 0.5, 2.0 * s_flReadMsAvg );
		double flRate = clamp( MIN( flMaxRate, flBudgetRate ), 10.0, 240.0 );
		if ( !s_bWorkerBusy && flNow - s_flLastPair >= 1.0 / flRate - 0.0005 )
		{
			s_flLastPair = flNow;
			bCapture = true;
		}
	}
	if ( !bCapture )
		return 1;
	s_PairPose = s_LastLookPose;
	s_PairPose.hasPosition = 0;  // set by NotePairPosition during the first draw
	return 2;
}

void FortCraft_SetOverlayPass( int pass )
{
	s_nPass = s_bLinked ? pass : -1;
	++s_nRenderSerial;
}

void FortCraft_ClearForOverlay()
{
	// Called at the start of TF2's main 3D view (before entities). Once per draw only.
	if ( s_nClearedSerial == s_nRenderSerial )
		return;
	s_nClearedSerial = s_nRenderSerial;
	if ( !s_bLinked )
		return;

	// Blank background (black, or white for the second half of a pair), then Minecraft's blocks
	// into depth. Everything TF2 draws after this (rockets, smoke, explosions, weapon, HUD) is
	// the overlay.
	{
		CMatRenderContextPtr pRenderContext( materials );
		unsigned char c = s_nPass == 1 ? 255 : 0;
		pRenderContext->ClearColor4ub( c, c, c, 255 );
		pRenderContext->ClearBuffers( true, true );
	}
	DrawBlockDepth();
	DrawBlobShadows();
}

void FortCraft_ReadOverlay( int width, int height )
{
	if ( s_nPass < 0 || !s_bLinked || width <= 0 || height <= 0 )
		return;
	width = MIN( width, (int)proto::kMaxOverlayWidth );
	height = MIN( height, (int)proto::kMaxOverlayHeight );
	if ( s_nPass == 1 && ( width != s_nCaptureW || height != s_nCaptureH ) )
		return;  // resized between the two halves; skip this pair
	s_nCaptureW = width;
	s_nCaptureH = height;

	if ( GpuOverlay() )
	{
		// GPU path: copy on the GPU, combine with a shader, publish when the GPU has finished.
		CMatRenderContextPtr pRenderContext( materials );
		pRenderContext->Flush( true );  // make sure everything TF2 drew this frame has reached the device
		FortCraftGpu_Grab( s_nPass, width, height );
		if ( s_nPass == 1 )
		{
			FortCraftGpu_Combine();
			++s_nPairs;
		}
		LogPerf( width, height, "gpu" );
		return;
	}

	double flStart = Plat_FloatTime();
	s_Capture[ s_nPass ].EnsureCapacity( width * height * 4 );
	CMatRenderContextPtr pRenderContext( materials );
	pRenderContext->ReadPixels( 0, 0, width, height, s_Capture[ s_nPass ].Base(), IMAGE_FORMAT_BGRX8888 );
	double flMs = 1000.0 * ( Plat_FloatTime() - flStart );
	s_flReadMsAvg = s_flReadMsAvg * 0.9 + flMs * 0.1;
	s_flSumRead += flMs / 1000.0;
	++s_nReads;

	if ( s_nPass == 1 && !s_bWorkerBusy )
	{
		// Hand the pair to the worker thread.
		if ( !s_hWorker )
			s_hWorker = CreateSimpleThread( WorkerMain, NULL );
		s_Capture[ 0 ].Swap( s_Work[ 0 ] );
		s_Capture[ 1 ].Swap( s_Work[ 1 ] );
		s_WorkPose = s_PairPose;
		s_nWorkW = width;
		s_nWorkH = height;
		s_bWorkerBusy = true;
		s_WorkReady.Set();
		++s_nPairs;
	}
	LogPerf( width, height, "cpu" );
}

// Publish a finished GPU overlay frame to Minecraft. Called every frame.
static void PublishGpuOverlay()
{
	if ( !GpuOverlay() )
		return;
	uint32_t front;
	if ( !FortCraftGpu_TakeFinished( &front ) )
		return;
	proto::OverlayGpu &g = At< proto::OverlayGpu >( proto::kOffOverlayGpu );
	uint64_t h0, h1;
	uint32_t w, h, gen;
	FortCraftGpu_GetShared( &h0, &h1, &w, &h, &gen );
	if ( gen != g.generation )
	{
		g.valid = 0;
		g.handle[ 0 ] = h0;
		g.handle[ 1 ] = h1;
		g.width = w;
		g.height = h;
		FortCraftGpu_Adapter( &g.vendorId, &g.luidLow, &g.luidHigh );
		Msg( "FortCraft: TF2 draws on graphics chip vendor %04x (adapter %08x:%08x)\n", g.vendorId, (unsigned)g.luidHigh, g.luidLow );
		*(volatile uint32_t *)&g.generation = gen;
		Msg( "FortCraft: GPU overlay textures %ux%u (generation %u)\n", w, h, gen );
	}
	At< proto::OverlayPose >( proto::kOffOverlayPose + ( front & 1 ) * sizeof( proto::OverlayPose ) ) = s_PairPose;
	*(volatile uint32_t *)&g.front = front;
	*(volatile uint32_t *)&g.valid = 1;
	*(volatile uint32_t *)&g.seq = g.seq + 1;
}

static void LogPerf( int width, int height, const char *pszPath )
{
	double flNow = Plat_FloatTime();
	if ( s_flLogStart == 0.0 )
	{
		s_flLogStart = flNow;
		s_nFrameAtLog = s_nFrame;
	}
	else if ( flNow - s_flLogStart >= 5.0 )
	{
		double flSpan = flNow - s_flLogStart;
		PerfLog( "FortCraft: perf %dx%d (%s): TF2 %.0f fps, overlay %.0f fps, read %.1f ms each (on TF2's frame), combine %.1f ms (worker) (fps_max %d)\n",
			width, height, pszPath, ( s_nFrame - s_nFrameAtLog ) / flSpan, s_nPairs / flSpan,
			1000.0 * s_flSumRead / MAX( 1, s_nReads ), 1000.0 * s_flSumCombine / MAX( 1, s_nPairs ), s_nOverlayFps );
		s_flLogStart = flNow;
		s_nFrameAtLog = s_nFrame;
		s_flSumRead = s_flSumCombine = 0.0;
		s_nReads = s_nPairs = 0;
	}
}

// TF2's own popups (map info, and the team and class menus on joining) are hidden: AutoJoin
// picks team and class. Menus Alex opened himself (, and . keys) and the scoreboard stay.
static void HideMenus()
{
	if ( !gViewPortInterface || s_bUserMenu )
		return;
	IViewPortPanel *pPanel = gViewPortInterface->GetActivePanel();
	if ( pPanel && V_strcmp( pPanel->GetName(), PANEL_SCOREBOARD ) != 0 )
	{
		Msg( "FortCraft: hiding TF2 menu '%s'\n", pPanel->GetName() );
		gViewPortInterface->ShowPanel( pPanel, false );
	}
}

// TF2's (hidden) window size. Each overlay copy makes TF2 wait, and the wait grows with the
// pixel count (~12 ms at 2560x1440 held the picture to ~15 fps; ~3 ms at 720p gave ~75 fps).
// Alex asked for 1080p. Always a standard 16:9 size: asked for Minecraft's exact shape, TF2
// picked the nearest mode it knows (1280x1024 for 1280x684, squashing the HUD). Minecraft
// scales the picture to its window. -fortcraft_overlay_height N changes the cap.
// Applied once the size has been steady for half a second.
static void MatchHostDisplay()
{
	const proto::HostDisplay &d = At< proto::HostDisplay >( proto::kOffHostDisplay );
	if ( d.width <= 0 || d.height <= 0 )
		return;
	// Low-end mode (-fortcraft_lowend, PLAY-FORTCRAFT-LOW-END.cmd): 720p, under half the pixels.
	static int s_nCapH = CommandLine()->ParmValue( "-fortcraft_overlay_height",
		CommandLine()->FindParm( "-fortcraft_lowend" ) ? 720 : 1080 );
	static const int kHeights[] = { 1080, 900, 720, 540 };
	int h = 540;
	for ( int i = 0; i < 4; ++i )
	{
		if ( kHeights[ i ] <= s_nCapH && kHeights[ i ] <= MAX( 540, (int)d.height ) )
		{
			h = kHeights[ i ];
			break;
		}
	}
	int w = h * 16 / 9;

	// Same frame rate as Minecraft, so neither game outruns the other.
	int fps = d.fpsLimit > 0 ? (int)d.fpsLimit : 300;
	if ( fps != s_nOverlayFps )
	{
		s_nOverlayFps = fps;
		Msg( "FortCraft: matching Minecraft's frame rate: fps_max %d\n", fps );
		engine->ClientCmd_Unrestricted( VarArgs( "fps_max %d\n", fps ) );
	}

	static int s_nWantW, s_nWantH, s_nRequestedW, s_nRequestedH;
	static double s_flSince;
	if ( w != s_nWantW || h != s_nWantH )
	{
		s_nWantW = w;
		s_nWantH = h;
		s_flSince = Plat_FloatTime();
		return;
	}
	// Ask once per new size. TF2 may only offer the nearest size it supports; if so, Minecraft
	// stretches the overlay the last few pixels rather than TF2 resizing again and again (each
	// mode change freezes TF2 for a moment).
	if ( ( w == s_nRequestedW && h == s_nRequestedH ) || ( w == ScreenWidth() && h == ScreenHeight() ) || Plat_FloatTime() - s_flSince < 0.5 )
		return;
	s_nRequestedW = w;
	s_nRequestedH = h;

	Msg( "FortCraft: resizing TF2 to %dx%d to match Minecraft\n", w, h );
	FortCraftPlat_RememberFocus();  // Minecraft, normally
	engine->ClientCmd_Unrestricted( VarArgs( "mat_setvideomode %d %d 1\n", w, h ) );
	if ( s_bHidden )
		s_flRehideAt = Plat_FloatTime() + 0.5;  // a mode change can show the window again
}

// When Minecraft has let go of the mouse (pause menu, chat, alt-tab), nobody may hold it.
// FortCraftPlat_ReleaseCursor undoes any lock (Windows: ClipCursor; Linux: SDL grab).
static void FreeCursorIfMinecraftLetGo()
{
	const proto::HostDisplay &d = At< proto::HostDisplay >( proto::kOffHostDisplay );
	bool bFree = ( d.flags & proto::kHostCursorFree ) != 0;
	static bool s_bWasFree;
	bool bChanged = bFree != s_bWasFree;
	s_bWasFree = bFree;
	if ( !bFree )
		return;

	FortCraftPlat_ReleaseCursor( Hdr().hostPid, bChanged, PlatLog );
}

// Jitter measurement. When TF2's server disagrees with where the client predicted the player
// (for example the two read a different set of Minecraft blocks), TF2 snaps the player back and
// smooths the view over cl_smoothtime (0.1 s); a burst of these is felt as jitter. TF2 keeps
// the leftover correction as a smoothing offset: a new error shows as that offset growing.
static void NotePredictionErrors( C_TFPlayer *pPlayer, const Vector &pos )
{
	static Vector s_vecLastSmooth;
	static double s_flWindowStart;
	static int s_nErrors, s_nLines;
	static float s_flMaxError;
	Vector vSmooth;
	pPlayer->GetPredictionErrorSmoothingVector( vSmooth );
	const float flGrowth = ( vSmooth - s_vecLastSmooth ).Length();
	if ( vSmooth.Length() > s_vecLastSmooth.Length() + 0.01f )
	{
		++s_nErrors;
		s_flMaxError = MAX( s_flMaxError, flGrowth );
		if ( s_nLines < 20 )  // the first few of each 5 s, with where and how the player moved
		{
			++s_nLines;
			const Vector vel = pPlayer->GetAbsVelocity();
			PerfLog( "FortCraft jitter: prediction error %.3f units (%.3f %.3f %.3f) at %.1f %.1f %.1f vel %.0f %.0f %.0f onGround=%d\n",
				flGrowth, vSmooth.x - s_vecLastSmooth.x, vSmooth.y - s_vecLastSmooth.y, vSmooth.z - s_vecLastSmooth.z,
				pos.x, pos.y, pos.z, vel.x, vel.y, vel.z, ( pPlayer->GetFlags() & FL_ONGROUND ) ? 1 : 0 );
		}
	}
	s_vecLastSmooth = vSmooth;
	const double flNow = Plat_FloatTime();
	if ( s_flWindowStart == 0.0 )
		s_flWindowStart = flNow;
	else if ( flNow - s_flWindowStart >= 5.0 )
	{
		PerfLog( "FortCraft jitter: %d prediction errors in %.0f s (largest %.3f units)\n", s_nErrors, flNow - s_flWindowStart, s_flMaxError );
		s_flWindowStart = flNow;
		s_nErrors = s_nLines = 0;
		s_flMaxError = 0.0f;
	}
}

// The backpack opens on page 1 (CBackpackPanel::OnShowPanel); the Minecraft items are on the
// last page. Once the panel is showing, turn it there (SetCurrentPage(-1) means the last page).
static void TurnBackpackToMinecraftItems()
{
	if ( s_flBackpackLastPageUntil == 0.0 )
		return;
	if ( Plat_FloatTime() > s_flBackpackLastPageUntil )
	{
		s_flBackpackLastPageUntil = 0.0;
		Msg( "FortCraft: backpack didn't open; not turning the page\n" );
		return;
	}
	CBackpackPanel *pBackpack = EconUI() ? EconUI()->GetBackpackPanel() : NULL;
	if ( !pBackpack || !pBackpack->IsVisible() )
		return;
	// The backpack resets itself to page 1 once more as it finishes opening (Alex: E showed page
	// 1 although the log said page 18), so hold it on the last page for a moment after it shows.
	static double s_flHoldUntil;
	if ( s_flHoldUntil == 0.0 )
		s_flHoldUntil = Plat_FloatTime() + 0.75;
	const int nLast = pBackpack->GetNumPages() - 1;
	if ( pBackpack->GetCurrentPage() != nLast )
	{
		pBackpack->SetCurrentPage( nLast );
		Msg( "FortCraft: backpack turned to page %d of %d (Minecraft items)\n", nLast + 1, nLast + 1 );
	}
	if ( Plat_FloatTime() > s_flHoldUntil )
	{
		s_flHoldUntil = 0.0;
		s_flBackpackLastPageUntil = 0.0;
	}
}

static void AutoJoin( C_TFPlayer *pPlayer )
{
	// Round settings for our local server. On the launch line these come too early (the
	// server's settings don't exist yet: "Unknown command"), so set them once we're in.
	static bool s_bRulesSet;
	if ( !s_bRulesSet )
	{
		s_bRulesSet = true;
		engine->ClientCmd_Unrestricted( "mp_waitingforplayers_time 0; mp_waitingforplayers_cancel 1; mp_timelimit 0; mp_winlimit 0; mp_maxrounds 0\n" );
		// Weapons reload by themselves (R is Minecraft's inventory; TF2's reload key is optional).
		engine->ClientCmd_Unrestricted( "cl_autoreload 1\n" );
		// Respawn straight away after dying (TF2 made Alex wait 16 seconds).
		engine->ClientCmd_Unrestricted( "mp_disable_respawn_times 1; mp_respawnwavetime 0\n" );

		// Several of the settings below are "cheat" settings that TF2 silently refuses without
		// sv_cheats 1, so that has to come first (it came last before, and TF2's map was drawn).
		engine->ClientCmd_Unrestricted( "sv_cheats 1\n" );

		// TF2's map isn't drawn at all: only the weapon, HUD, players, buildings and effects are
		// ever seen. Picture quality (textures, models, anti-aliasing...) is the player's own
		// from their real TF2, copied by tools/tf2_graphics.py at launch (Alex, 2026-10-10:
		// stop forcing the lowest settings); only what FortCraft needs a certain way is set here.
		engine->ClientCmd_Unrestricted(
			"r_drawworld 0; r_drawopaqueworld 0; r_drawtranslucentworld 0; r_drawdisp 0; r_drawbrushmodels 0; r_skybox 0;"
			" r_drawstaticprops 0; r_3dsky 0; r_drawdetailprops 0; r_drawropes 0; r_drawsprites 0;"
			// Visibility: TF2 picks what to draw from which parts of its map can see each other.
			// We stand outside TF2's map, so that hid things like the rocket model itself.
			" r_novis 1; r_occlusion 0;"
			" r_shadows 0; r_flashlightdepthtexture 0; r_dynamic 0; r_decals 0; mp_decals 0; r_drawbatchdecals 0;"
			" r_waterforceexpensive 0; r_waterforcereflectentities 0;"
			" mat_hdr_level 0; mat_disable_bloom 1; mat_motion_blur_enabled 0; mat_colorcorrection 0;"
			// No full-screen TF2 effects over Minecraft (burning, Jarate, Bonk, Ubercharge tints;
			// strafing view roll).
			" r_drawscreenoverlay 0; cl_rollangle 0;"
			// No environment-map reflections: TF2's map has no cubemaps here, so shiny weapons
			// reflected a bright default one and glowed white (Alex's minigun, 2026-10-10).
			" mat_specular 0;"
			// No rim light either: TF2 brightens model edges from the light around them, which
			// left a white sheen along weapons (minigun barrel, 2026-10-10).
			" r_rimlight 0;"
			// The MvM wave panel during village raids lists every robot kind all the time.
			" cl_mvm_wave_status_visible_during_wave 1;"
			// TF2's one-time "HUD 3D character: keep it enabled?" question popped up mid-play,
			// where testers couldn't answer it (2026-10-12): count it as answered (kept on).
			" cl_hud_playerclass_playermodel_showed_confirm_dialog 1;"
			" mat_vsync 0; cl_ragdoll_physics_enable 0; cl_phys_props_enable 0; tf_particles_disable_weather 1\n" );

		// The weapon was drawn pitch black: TF2 lights it from its map at the player's spot, and
		// we roam far outside TF2's map, where there is no light. Models now get their own even
		// light from Minecraft's brightness (C_BaseAnimating::InternalDrawModel), so no full-bright.
		engine->ClientCmd_Unrestricted( "mat_fullbright 0\n" );
	}

	// Pick a team and class by ourselves, since nobody can click TF2's menus.
	if ( Plat_FloatTime() - s_flLastJoinTry < 2.0 )
		return;
	if ( pPlayer->GetTeamNumber() < FIRST_GAME_TEAM )
	{
		s_flLastJoinTry = Plat_FloatTime();
		engine->ClientCmd_Unrestricted( "jointeam red" );
	}
	else if ( pPlayer->GetPlayerClass()->GetClassIndex() == TF_CLASS_UNDEFINED )
	{
		s_flLastJoinTry = Plat_FloatTime();
		engine->ClientCmd_Unrestricted( "joinclass soldier" );
	}
}

// TF2 position (units, Z-up, +Y north) to Minecraft (blocks, Y-up, -Z north), around the anchor.
static void ToMinecraft( const Vector &pos, float &x, float &y, float &z )
{
	x = ( pos.x - s_vecOrigin.x ) / UNITS_PER_BLOCK + 0.5f;
	y = ( pos.z - s_vecOrigin.z ) / UNITS_PER_BLOCK + (float)MC_FLOOR_Y;
	z = -( pos.y - s_vecOrigin.y ) / UNITS_PER_BLOCK + 0.5f;
}

// TF2's map is invisible in Minecraft, but some of it is entities rather than world geometry
// (resupply lockers, doors, spawn-room walls, props), which r_drawworld doesn't cover. Hide
// those every frame: anything with a brush model, and props. Projectiles, players, the weapon
// and effects stay.
static void HideMapEntities()
{
	for ( C_BaseEntity *pEnt = ClientEntityList().FirstBaseEntity(); pEnt; pEnt = ClientEntityList().NextBaseEntity( pEnt ) )
	{
		if ( pEnt->IsDormant() || !pEnt->GetClientClass() || pEnt->IsPlayer() )
			continue;
		const char *pszClass = pEnt->GetClientClass()->m_pNetworkName;
		if ( V_stristr( pszClass, "ViewModel" ) || V_stristr( pszClass, "Projectile" ) || V_stristr( pszClass, "Rocket" ) || V_stristr( pszClass, "Grenade" ) )
			continue;
		const model_t *pModel = pEnt->GetModel();
		bool bBrush = pModel && modelinfo->GetModelType( pModel ) == mod_brush;
		if ( bBrush || V_stristr( pszClass, "Prop" ) || V_stristr( pszClass, "World" ) )
			pEnt->AddEffects( EF_NODRAW );
	}
}

// ---- Raiders as Mann vs. Machine robots (protocol RaidRobots, v63) ----------------------------
// Alex, 2026-10-11: "native TF2 MvM robots, but instead of navmesh they use Minecraft's movement".
// Minecraft keeps the mobs (AI, pathfinding, raid waves, damage) and stops drawing them; here TF2
// draws a robot in each one's place: TF2's robot model and weapon, its run/stand/air animations
// blended by how the mob moves, attack and flinch gestures, robot footsteps and voices, and gibs
// when it dies. The robot is scaled to the mob's hitbox, which is what TF2's weapons hit.
class C_FortCraftRobot : public C_BaseAnimating
{
public:
	DECLARE_CLASS( C_FortCraftRobot, C_BaseAnimating );
	int m_nGestureSeq = -1;
	float m_flGestureCycle = 1.0f;

	// One gesture (attack swing, bow release, flinch) layered over the movement sequence, as
	// TF2's players do with their animation layers.
	virtual void AccumulateLayers( IBoneSetup &boneSetup, Vector pos[], Quaternion q[], float currentTime ) OVERRIDE
	{
		BaseClass::AccumulateLayers( boneSetup, pos, q, currentTime );
		if ( m_nGestureSeq >= 0 && m_flGestureCycle < 1.0f && m_nGestureSeq < boneSetup.GetStudioHdr()->GetNumSeq() )
			boneSetup.AccumulatePose( pos, q, m_nGestureSeq, m_flGestureCycle, 1.0f, currentTime, m_pIk );
	}
};

struct RobotKind
{
	const char *pszModel, *pszWeapon, *pszExtra;
	const char *pszVoice;       // sound script class: Sniper.MVM_PainSharp01 ...
	bool bAlwaysGiant;          // the ravager's Giant Heavy
	const char *pszGiantModel;  // MvM's giant model for this class, if it has one
	bool bMighty;               // giants of this class have their own voice lines (M_MVM_*)
	const char *pszGiantStep;
	int nPain, nBattleCry, nCheers;
	Activity stand, run, air, attack, deployedStand, deployedRun;
};

// Indexed by the kind Minecraft sends (Robots.java): 1 Bowman, 2 Demoknight, 3 Medic, 4 Scout,
// 5 Giant Heavy, 6 Jarate Sniper.
static const RobotKind kRobotKinds[] = {
	{ NULL, NULL, NULL, NULL, false, NULL, false, NULL, 0, 0, 0, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID },
	{ "models/bots/sniper/bot_sniper.mdl", "models/weapons/c_models/c_bow/c_bow.mdl", NULL, "Sniper", false, NULL, false, "MVM.GiantSoldierStep", 4, 6, 8,
		ACT_MP_STAND_ITEM2, ACT_MP_RUN_ITEM2, ACT_MP_AIRWALK_ITEM2, ACT_MP_ATTACK_STAND_ITEM2, ACT_MP_DEPLOYED_IDLE_ITEM2, ACT_INVALID },
	{ "models/bots/demo/bot_demo.mdl", "models/weapons/c_models/c_claymore/c_claymore.mdl", "models/weapons/c_models/c_targe/c_targe.mdl", "Demoman", false,
		"models/bots/demo_boss/bot_demo_boss.mdl", true, "MVM.GiantDemomanStep", 7, 7, 8,
		ACT_MP_STAND_MELEE, ACT_MP_RUN_MELEE, ACT_MP_AIRWALK_MELEE, ACT_MP_ATTACK_STAND_MELEE, ACT_INVALID, ACT_INVALID },
	{ "models/bots/medic/bot_medic.mdl", "models/weapons/c_models/c_medigun/c_medigun.mdl", NULL, "Medic", false, NULL, false, "MVM.GiantSoldierStep", 8, 6, 6,
		ACT_MP_STAND_SECONDARY, ACT_MP_RUN_SECONDARY, ACT_MP_AIRWALK_SECONDARY, ACT_MP_ATTACK_STAND_SECONDARY, ACT_INVALID, ACT_INVALID },
	{ "models/bots/scout/bot_scout.mdl", "models/weapons/c_models/c_bat.mdl", NULL, "Scout", false,
		"models/bots/scout_boss/bot_scout_boss.mdl", true, "MVM.GiantScoutStep", 8, 5, 6,
		ACT_MP_STAND_MELEE, ACT_MP_RUN_MELEE, ACT_MP_AIRWALK_MELEE, ACT_MP_ATTACK_STAND_MELEE, ACT_INVALID, ACT_INVALID },
	// The Giant Heavy spins up its minigun while it hunts and fires when the ravager attacks.
	{ "models/bots/heavy_boss/bot_heavy_boss.mdl", "models/weapons/c_models/c_minigun/c_minigun.mdl", NULL, "Heavy", true,
		"models/bots/heavy_boss/bot_heavy_boss.mdl", true, "MVM.GiantHeavyStep", 5, 6, 8,
		ACT_MP_STAND_PRIMARY, ACT_MP_RUN_PRIMARY, ACT_MP_AIRWALK_PRIMARY, ACT_MP_ATTACK_STAND_PRIMARY, ACT_MP_DEPLOYED_IDLE, ACT_MP_DEPLOYED_PRIMARY },
	{ "models/bots/sniper/bot_sniper.mdl", "models/weapons/c_models/urinejar.mdl", NULL, "Sniper", false, NULL, false, "MVM.GiantSoldierStep", 4, 6, 8,
		ACT_MP_STAND_ITEM1, ACT_MP_RUN_ITEM1, ACT_MP_AIRWALK_ITEM1, ACT_MP_ATTACK_STAND_ITEM1, ACT_INVALID, ACT_INVALID },
	// 7: not a robot, a witch's Jarate jar in flight.
	{ "models/weapons/c_models/urinejar.mdl", NULL, NULL, NULL, false, NULL, false, NULL, 0, 0, 0,
		ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID },
	// 8-10: projectiles: a Bowman's arrow, a Soldier's rocket, a Demoman's pipe.
	{ "models/weapons/w_models/w_arrow.mdl", NULL, NULL, NULL, false, NULL, false, NULL, 0, 0, 0,
		ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID },
	{ "models/weapons/w_models/w_rocket.mdl", NULL, NULL, NULL, false, NULL, false, NULL, 0, 0, 0,
		ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID },
	{ "models/weapons/w_models/w_grenade_grenadelauncher.mdl", NULL, NULL, NULL, false, NULL, false, NULL, 0, 0, 0,
		ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID },
	// 11 Soldier (the boss is a huge one), 12 Demoman, 13 Sniper, 14 Spy, 15 full-size Scout.
	{ "models/bots/soldier/bot_soldier.mdl", "models/weapons/c_models/c_rocketlauncher/c_rocketlauncher.mdl", NULL, "Soldier", false,
		"models/bots/soldier_boss/bot_soldier_boss.mdl", true, "MVM.GiantSoldierStep", 8, 6, 6,
		ACT_MP_STAND_PRIMARY, ACT_MP_RUN_PRIMARY, ACT_MP_AIRWALK_PRIMARY, ACT_MP_ATTACK_STAND_PRIMARY, ACT_INVALID, ACT_INVALID },
	{ "models/bots/demo/bot_demo.mdl", "models/weapons/c_models/c_grenadelauncher/c_grenadelauncher.mdl", NULL, "Demoman", false,
		"models/bots/demo_boss/bot_demo_boss.mdl", true, "MVM.GiantDemomanStep", 7, 7, 8,
		ACT_MP_STAND_PRIMARY, ACT_MP_RUN_PRIMARY, ACT_MP_AIRWALK_PRIMARY, ACT_MP_ATTACK_STAND_PRIMARY, ACT_INVALID, ACT_INVALID },
	{ "models/bots/sniper/bot_sniper.mdl", "models/weapons/c_models/c_sniperrifle/c_sniperrifle.mdl", NULL, "Sniper", false, NULL, false, "MVM.GiantSoldierStep", 4, 6, 8,
		ACT_MP_STAND_PRIMARY, ACT_MP_RUN_PRIMARY, ACT_MP_AIRWALK_PRIMARY, ACT_MP_ATTACK_STAND_PRIMARY_DEPLOYED, ACT_MP_DEPLOYED_IDLE, ACT_MP_DEPLOYED_PRIMARY },
	{ "models/bots/spy/bot_spy.mdl", "models/weapons/c_models/c_knife/c_knife.mdl", NULL, "Spy", false, NULL, false, "MVM.GiantSoldierStep", 4, 4, 8,
		ACT_MP_STAND_MELEE, ACT_MP_RUN_MELEE, ACT_MP_AIRWALK_MELEE, ACT_MP_ATTACK_STAND_MELEE, ACT_INVALID, ACT_INVALID },
	{ "models/bots/scout/bot_scout.mdl", "models/weapons/c_models/c_scattergun.mdl", NULL, "Scout", false,
		"models/bots/scout_boss/bot_scout_boss.mdl", true, "MVM.GiantScoutStep", 8, 5, 6,
		ACT_MP_STAND_PRIMARY, ACT_MP_RUN_PRIMARY, ACT_MP_AIRWALK_PRIMARY, ACT_MP_ATTACK_STAND_PRIMARY, ACT_INVALID, ACT_INVALID },
	// 16: MvM's Tank (a ravager in the last raid wave). No voice, no steps.
	{ "models/bots/boss_bot/boss_tank.mdl", NULL, NULL, NULL, true, "models/bots/boss_bot/boss_tank.mdl", false, NULL, 0, 0, 0,
		ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID, ACT_INVALID },
};

struct RobotState
{
	int id = 0;
	int kind = 0;
	C_FortCraftRobot *pModel = NULL;
	C_BaseAnimating *pWeapon = NULL, *pExtra = NULL;
	// The same entities by handle: if TF2 itself removes them (a map change), the pointers above
	// are dropped instead of used.
	CHandle< C_BaseAnimating > hModel, hWeapon, hExtra;
	uint16_t flags = 0;      // last frame's
	Vector vecLast = vec3_origin;
	Vector vecVelocity = vec3_origin;  // smoothed, TF2 units per second
	float flStepUnits = 0.0f;          // walked since the last footstep
	double flNextVoice = 0.0;
	double flNextCast = 0.0;
	double flRetryAt = 0.0;  // model couldn't be made (not precached yet): try again then
	float flFacing = 0.0f;   // body yaw drawn last frame (turning to the player is eased)
	bool bAimingAtPlayer = false;
	bool bGiant = false;     // the model made is the giant one
	CNewParticleEffect *pMuzzle = NULL;  // Giant Heavy's minigun flash while firing
	CNewParticleEffect *pBeam = NULL;    // Medic's healing beam
	CNewParticleEffect *pBurn = NULL;    // on fire
	CNewParticleEffect *pDrips = NULL;   // Jarate or Mad Milk
	int nBeamTarget = 0;
	float flCloak = 0.0f;                // Spy: 0 seen .. 1 cloaked
	uint8_t extra = 0;                   // last frame's kRobotEx*
	bool bTrail = false;                 // projectile: trail started
	double flNextTracer = 0.0;
	bool bDead = false;
	bool bSeen = false;      // in this frame's list
};
static CUtlVector< RobotState > s_Robots;

struct RobotGib
{
	C_BaseAnimating *pModel;
	CHandle< C_BaseAnimating > hModel;
	Vector pos, vel;
	QAngle ang, spin;
	double flBorn;
	bool bResting;
};
static CUtlVector< RobotGib > s_RobotGibs;
static const double kGibLife = 8.0;

static Vector RobotToTF2( float x, float y, float z )
{
	return Vector( ( x - 0.5f ) * UNITS_PER_BLOCK + s_vecOrigin.x, -( z - 0.5f ) * UNITS_PER_BLOCK + s_vecOrigin.y,
		( y - (float)MC_FLOOR_Y ) * UNITS_PER_BLOCK + s_vecOrigin.z );
}

static void RobotSound( const char *pszName, const Vector &pos )
{
	C_BaseEntity::PrecacheScriptSound( pszName );
	CLocalPlayerFilter filter;
	C_BaseEntity::EmitSound( filter, SOUND_FROM_WORLD, pszName, &pos );
}

// "Sniper.MVM_PainSharp03", "Heavy.M_MVM_BattleCry05" ...
static void RobotVoice( const RobotKind &k, bool bGiant, const char *pszLine, int nCount, const Vector &pos )
{
	if ( nCount <= 0 || !k.pszVoice )
		return;
	RobotSound( VarArgs( "%s.%sMVM_%s%02d", k.pszVoice, bGiant && k.bMighty ? "M_" : "", pszLine, RandomInt( 1, nCount ) ), pos );
}

static C_BaseAnimating *RobotPart( const char *pszModel, C_BaseAnimating *pParent )
{
	if ( !pszModel )
		return NULL;
	C_BaseAnimating *pPart = new C_BaseAnimating;
	if ( !pPart->InitializeAsClientEntity( pszModel, RENDER_GROUP_OPAQUE_ENTITY ) )
	{
		pPart->Release();
		return NULL;
	}
	pPart->m_nSkin = 1;  // BLU, like MvM's robots
	pPart->FollowEntity( pParent, true );
	return pPart;
}

static void ReleaseRobot( RobotState &r )
{
	if ( r.hModel.Get() != r.pModel )
		r.pModel = NULL;
	if ( r.hWeapon.Get() != r.pWeapon )
		r.pWeapon = NULL;
	if ( r.hExtra.Get() != r.pExtra )
		r.pExtra = NULL;
	if ( r.pWeapon )
		r.pWeapon->Release();
	if ( r.pExtra )
		r.pExtra->Release();
	if ( r.pModel )
		r.pModel->Release();
	r.pWeapon = r.pExtra = NULL;
	r.pModel = NULL;
	r.pMuzzle = NULL;  // went with the weapon
	r.pBeam = NULL;
	r.pBurn = r.pDrips = NULL;
	r.bTrail = false;
}

static int RobotSequence( C_BaseAnimating *pModel, Activity act )
{
	return act == ACT_INVALID ? -1 : pModel->SelectWeightedSequence( act );
}

// The robot breaks apart like MvM's do: its model's gib pieces fly off and bounce on Minecraft's
// blocks, then fade.
static void RobotGibs( RobotState &r )
{
	if ( !r.pModel || UTIL_IsLowViolence() )
		return;
	CUtlVector< breakmodel_t > list;
	BuildGibList( list, r.pModel->GetModelIndex(), 1.0f, COLLISION_GROUP_NONE );
	const Vector origin = r.pModel->GetAbsOrigin();
	const QAngle angles = r.pModel->GetAbsAngles();
	const float flScale = r.pModel->GetModelScale();
	// MvM's giant gib pieces are already modelled at giant size: scaling them by the giant's 1.75
	// again made them enormous (Alex, 2026-10-11).
	const RobotKind &k = kRobotKinds[ r.kind ];
	const float flGibScale = r.bGiant && k.pszGiantModel ? flScale / 1.75f : flScale;
	matrix3x4_t xf;
	AngleMatrix( angles, origin, xf );
	for ( int i = 0; i < list.Count() && s_RobotGibs.Count() < 96; ++i )
	{
		C_BaseAnimating *pGib = new C_BaseAnimating;
		if ( !pGib->InitializeAsClientEntity( list[ i ].modelName, RENDER_GROUP_OPAQUE_ENTITY ) )
		{
			pGib->Release();
			continue;
		}
		pGib->m_nSkin = 1;
		pGib->SetModelScale( flGibScale );
		RobotGib g;
		g.pModel = pGib;
		g.hModel = pGib;
		VectorTransform( list[ i ].offset * flScale, xf, g.pos );
		g.pos.z += 8.0f * flScale;
		g.ang = angles;
		g.vel = r.vecVelocity + Vector( RandomFloat( -160, 160 ), RandomFloat( -160, 160 ), RandomFloat( 220, 380 ) );
		g.spin = QAngle( RandomFloat( -360, 360 ), RandomFloat( -360, 360 ), RandomFloat( -360, 360 ) );
		g.flBorn = Plat_FloatTime();
		g.bResting = false;
		pGib->SetAbsOrigin( g.pos );
		pGib->SetAbsAngles( g.ang );
		s_RobotGibs.AddToTail( g );
	}
}

static void UpdateRobotGibs( float dt )
{
	const double flNow = Plat_FloatTime();
	for ( int i = s_RobotGibs.Count() - 1; i >= 0; --i )
	{
		RobotGib &g = s_RobotGibs[ i ];
		const double flAge = flNow - g.flBorn;
		if ( g.hModel.Get() != g.pModel )
		{
			s_RobotGibs.Remove( i );  // TF2 removed it already
			continue;
		}
		if ( flAge > kGibLife )
		{
			g.pModel->Release();
			s_RobotGibs.Remove( i );
			continue;
		}
		if ( !g.bResting )
		{
			g.vel.z -= 800.0f * dt;
			const Vector next = g.pos + g.vel * dt;
			// Collide with the gib's whole turned shape, not a tiny box at its origin: pieces sank
			// half into the ground (Alex, 2026-10-11).
			Vector vecLocalMins, vecLocalMaxs, vecMins, vecMaxs;
			// The piece's own animation box (tight); the render box can be much bigger than the mesh.
			CStudioHdr *pHdr = g.pModel->GetModelPtr();
			if ( pHdr && pHdr->GetNumSeq() > 0 )
			{
				vecLocalMins = pHdr->pSeqdesc( 0 ).bbmin * g.pModel->GetModelScale();
				vecLocalMaxs = pHdr->pSeqdesc( 0 ).bbmax * g.pModel->GetModelScale();
			}
			else
			{
				g.pModel->GetRenderBounds( vecLocalMins, vecLocalMaxs );  // already scaled
			}
			matrix3x4_t rot;
			AngleMatrix( g.ang, rot );
			RotateAABB( rot, vecLocalMins, vecLocalMaxs, vecMins, vecMaxs );
			trace_t tr;
			FortCraft_ClearTrace( g.pos, next, tr );
			FortCraft_ClipHullTrace( g.pos, next, vecMins, vecMaxs, tr );
			if ( tr.DidHit() )
			{
				g.pos = tr.endpos;
				// Bounce off the block face, losing most of the speed.
				const float flInto = DotProduct( g.vel, tr.plane.normal );
				g.vel = ( g.vel - tr.plane.normal * ( 1.6f * flInto ) ) * 0.4f;
				g.spin *= 0.5f;
				if ( tr.plane.normal.z > 0.7f && g.vel.Length() < 60.0f )
					g.bResting = true;
			}
			else
			{
				g.pos = next;
			}
			g.ang += g.spin * dt;
			g.pModel->SetAbsOrigin( g.pos );
			g.pModel->SetAbsAngles( g.ang );
		}
		// Fade over the last second.
		const float flLeft = (float)( kGibLife - flAge );
		if ( flLeft < 1.0f )
		{
			g.pModel->SetRenderMode( kRenderTransTexture );
			g.pModel->SetRenderColorA( (byte)( 255.0f * MAX( flLeft, 0.0f ) ) );
		}
	}
}

static void ReleaseAllRobots()
{
	FOR_EACH_VEC( s_Robots, i )
		ReleaseRobot( s_Robots[ i ] );
	s_Robots.RemoveAll();
	FOR_EACH_VEC( s_RobotGibs, i )
	{
		if ( s_RobotGibs[ i ].hModel.Get() == s_RobotGibs[ i ].pModel )
			s_RobotGibs[ i ].pModel->Release();
	}
	s_RobotGibs.RemoveAll();
}

static void UpdateRobot( RobotState &r, const proto::RaidRobot &in, float dt )
{
	const RobotKind &k = kRobotKinds[ r.kind ];
	const uint16_t f = in.flags, was = r.flags;
	const Vector pos = RobotToTF2( in.x, in.y, in.z );
	auto rose = [ & ]( uint16_t bit ) { return ( f & bit ) && !( was & bit ); };

	if ( r.bDead )
		return;
	if ( r.pModel && r.hModel.Get() != r.pModel )
	{
		// TF2 removed our entities (a map change): make new ones.
		r.pModel = NULL;
		r.pWeapon = r.pExtra = NULL;
	}
	if ( r.pWeapon && r.hWeapon.Get() != r.pWeapon )
		r.pWeapon = NULL;
	if ( r.pExtra && r.hExtra.Get() != r.pExtra )
		r.pExtra = NULL;
	const bool bGiant = k.bAlwaysGiant || ( f & proto::kRobotGiant ) != 0;
	if ( f & proto::kRobotDead )
	{
		// Dead: voice, gibs (and a giant's explosion), and the robot is gone.
		r.bDead = true;
		if ( r.pModel && k.pszVoice )
			RobotSound( VarArgs( "%s.%sMVM_Death", k.pszVoice, bGiant && k.bMighty ? "M_" : "" ), pos );
		if ( r.kind == proto::kRobotKindTank )
		{
			RobotSound( "MVM.TankExplodes", pos );
			DispatchParticleEffect( "ExplosionCore_MidAir", pos + Vector( 0, 0, 60 ), QAngle( 0, 0, 0 ) );
		}
		else if ( bGiant )
			RobotSound( r.kind == 5 ? "MVM.GiantHeavyExplodes" : "MVM.GiantCommonExplodes", pos );
		RobotGibs( r );
		ReleaseRobot( r );
		return;
	}
	if ( r.kind >= proto::kRobotKindJar && r.kind <= proto::kRobotKindPipe )
	{
		// A projectile: the jar tumbles; arrows, rockets and pipes point along their path, rockets
		// and pipes trail smoke.
		if ( !r.pModel )
		{
			if ( modelinfo->GetModelIndex( k.pszModel ) == -1 )
				return;
			r.pModel = new C_FortCraftRobot;
			if ( !r.pModel->InitializeAsClientEntity( k.pszModel, RENDER_GROUP_OPAQUE_ENTITY ) )
			{
				r.pModel->Release();
				r.pModel = NULL;
				return;
			}
			r.hModel = r.pModel;
			r.pModel->m_nSkin = 1;
			r.vecLast = pos;
			r.flFacing = AngleNormalize( -in.bodyYaw - 90.0f );
		}
		if ( !r.bTrail && ( r.kind == proto::kRobotKindRocket || r.kind == proto::kRobotKindPipe ) )
		{
			r.bTrail = true;
			const bool bCrit = ( in.extra & proto::kRobotExBoss ) != 0;
			r.pModel->ParticleProp()->Create( r.kind == proto::kRobotKindRocket ? ( bCrit ? "critical_rocket_blue" : "rockettrail" )
				: ( bCrit ? "critical_pipe_blue" : "pipebombtrail_blue" ), PATTACH_ABSORIGIN_FOLLOW );
		}
		const Vector vecFlown = pos - r.vecLast;
		QAngle angles = r.pModel->GetAbsAngles();
		if ( r.kind == proto::kRobotKindJar )
			angles = QAngle( fmodf( (float)Plat_FloatTime() * 720.0f, 360.0f ), AngleNormalize( -in.bodyYaw - 90.0f ), 0 );
		else if ( vecFlown.LengthSqr() > 0.25f )
			VectorAngles( vecFlown, angles );
		if ( r.kind == proto::kRobotKindPipe )
			angles.z = fmodf( (float)Plat_FloatTime() * 540.0f, 360.0f );  // pipes spin
		r.vecLast = pos;
		r.pModel->SetAbsOrigin( pos + Vector( 0, 0, r.kind == proto::kRobotKindJar ? 4.0f : 0.0f ) );
		r.pModel->SetAbsAngles( angles );
		return;
	}
	if ( r.pModel && r.bGiant != bGiant )
		ReleaseRobot( r );  // became a giant: make the giant model
	const char *pszModel = bGiant && k.pszGiantModel ? k.pszGiantModel : k.pszModel;
	if ( !r.pModel )
	{
		if ( Plat_FloatTime() < r.flRetryAt )
			return;
		// The client can only make models the server precached (CTFPlayer::PrecacheTFPlayer
		// precaches the robots for FortCraft); before that, retry every 2 s.
		if ( modelinfo->GetModelIndex( pszModel ) == -1 )
		{
			static double s_flWarnAt;
			if ( Plat_FloatTime() >= s_flWarnAt )
			{
				s_flWarnAt = Plat_FloatTime() + 10.0;
				Warning( "FortCraft: robot model %s isn't precached; raiders can't be drawn yet\n", pszModel );
			}
			r.flRetryAt = Plat_FloatTime() + 2.0;
			return;
		}
		r.pModel = new C_FortCraftRobot;
		if ( !r.pModel->InitializeAsClientEntity( pszModel, RENDER_GROUP_OPAQUE_ENTITY ) )
		{
			r.pModel->Release();
			r.pModel = NULL;
			r.flRetryAt = Plat_FloatTime() + 2.0;
			return;
		}
		Msg( "FortCraft: raider %d drawn as robot %s\n", r.id, pszModel );
		if ( r.kind == proto::kRobotKindTank )
			RobotSound( "MVM.TankStart", pos );
		r.bGiant = bGiant;
		if ( bGiant )
			RobotVoice( k, true, "BattleCry", k.nBattleCry, pos );
		r.pModel->m_nSkin = 1;
		r.pWeapon = RobotPart( k.pszWeapon, r.pModel );
		r.pExtra = RobotPart( k.pszExtra, r.pModel );
		r.hModel = r.pModel;
		r.hWeapon = r.pWeapon;
		r.hExtra = r.pExtra;
		r.vecLast = pos;
		r.vecVelocity.Init();
		r.flStepUnits = 0.0f;
		r.flNextVoice = Plat_FloatTime() + RandomFloat( 2.0f, 6.0f );
	}
	C_FortCraftRobot *pModel = r.pModel;

	// Size: the robot as tall as the mob's hitbox (TF2's players are 82 units tall); giants
	// a bit bigger still.
	float flScale = in.height * UNITS_PER_BLOCK / 82.0f;
	if ( r.kind == proto::kRobotKindTank )
		flScale = in.height * UNITS_PER_BLOCK / 160.0f;  // the Tank's model is about 160 units tall
	else if ( k.bAlwaysGiant )
		flScale = MAX( flScale, 1.75f );  // MvM's giants are 1.75x
	if ( fabsf( pModel->GetModelScale() - flScale ) > 0.01f )
		pModel->SetModelScale( flScale );

	// Movement from Minecraft's positions (teleports and re-centring aren't steps).
	Vector delta = pos - r.vecLast;
	r.vecLast = pos;
	if ( dt > 0.0f && delta.Length() < 4.0f * UNITS_PER_BLOCK )
	{
		const Vector v = delta / dt;
		r.vecVelocity = r.vecVelocity * 0.8f + v * 0.2f;
	}
	const float flSpeed = r.vecVelocity.Length2D();
	const bool bGround = ( f & proto::kRobotGround ) != 0;
	const bool bCharging = ( f & proto::kRobotCharging ) != 0;
	// Drawn bow (Bowman) or spun-up minigun (Giant Heavy hunting).
	const bool bDeployed = r.kind == 1 ? bCharging : ( r.kind == 5 || r.kind == 13 ) ? ( f & proto::kRobotAggressive ) != 0 : false;
	// Ubercharged robots glow in MvM's blue Uber skin.
	const bool bUber = ( in.extra & proto::kRobotExUber ) != 0;
	pModel->m_nSkin = bUber ? 3 : 1;

	// Facing: Minecraft yaw 0 faces south (+Z), TF2 yaw 0 faces east (+X). Robots look at the
	// player (Alex): within 24 blocks the head and aim follow you, and one standing still, or
	// fighting, turns its body to you too, like MvM's bots facing their target.
	float flBodyYaw = AngleNormalize( -in.bodyYaw - 90.0f );
	float flHeadYaw = AngleNormalize( -in.headYaw - 90.0f );
	float flAimPitch = -in.pitch;
	r.bAimingAtPlayer = false;
	C_TFPlayer *pLocal = C_TFPlayer::GetLocalTFPlayer();
	if ( pLocal && pLocal->IsAlive() && !( f & proto::kRobotCelebrating ) )
	{
		const Vector eye = pos + Vector( 0, 0, in.height * UNITS_PER_BLOCK * 0.9f );
		const Vector to = pLocal->WorldSpaceCenter() - eye;
		if ( to.Length() < 24.0f * UNITS_PER_BLOCK )
		{
			QAngle look;
			VectorAngles( to, look );
			flHeadYaw = look.y;
			flAimPitch = -AngleNormalize( look.x );
			// MvM's bots face their target even while running (the run blends sideways or
			// backwards); Alex: "none of the bots are really aiming at me".
			flBodyYaw = ApproachAngle( flHeadYaw, r.flFacing, 540.0f * MAX( dt, 0.001f ) );
			r.bAimingAtPlayer = true;
		}
	}
	r.flFacing = flBodyYaw;
	pModel->SetAbsOrigin( pos );
	pModel->SetAbsAngles( QAngle( 0, flBodyYaw, 0 ) );

	// Movement sequence.
	int nSeq;
	// Slow walks count as moving too, or the robot slid along in its standing pose (Alex).
	const bool bMoving = flSpeed > 0.04f * UNITS_PER_BLOCK;
	if ( !bGround )
		nSeq = RobotSequence( pModel, k.air );
	else if ( bMoving )
		nSeq = RobotSequence( pModel, bDeployed && k.deployedRun != ACT_INVALID ? k.deployedRun : k.run );
	else if ( ( f & proto::kRobotCelebrating ) && pModel->LookupSequence( "taunt01" ) >= 0 )
		nSeq = pModel->LookupSequence( "taunt01" );
	else
		nSeq = RobotSequence( pModel, bDeployed && k.deployedStand != ACT_INVALID ? k.deployedStand : k.stand );
	if ( nSeq < 0 )
		nSeq = RobotSequence( pModel, k.stand );
	if ( nSeq < 0 )
		nSeq = 0;
	if ( pModel->GetSequence() != nSeq )
	{
		pModel->SetSequence( nSeq );
		pModel->SetCycle( 0.0f );
	}

	// Run direction relative to the body (TF2's move_x / move_y), aim from the head.
	float flRate = 1.0f;
	if ( bMoving )
	{
		// As TF2's own player animation does: the run's full stride is reached at its own ground
		// speed; slower, the move pose shrinks towards standing (shorter steps, same pace), and
		// faster, the run plays faster. Before, slow robots took full strides slowly and slid.
		const float flMoveYaw = RAD2DEG( atan2f( r.vecVelocity.y, r.vecVelocity.x ) );
		const float flRel = DEG2RAD( AngleNormalize( flMoveYaw - flBodyYaw ) );
		pModel->SetPoseParameter( "move_x", cosf( flRel ) );
		pModel->SetPoseParameter( "move_y", -sinf( flRel ) );
		const float flGround = pModel->GetSequenceGroundSpeed( nSeq ) * flScale;
		float flAmount = 1.0f;
		if ( flGround > 1.0f )
		{
			flAmount = MIN( flSpeed / flGround, 1.0f );
			flRate = MAX( flSpeed / flGround, 1.0f );
			flRate = MIN( flRate, 3.0f );
		}
		pModel->SetPoseParameter( "move_x", cosf( flRel ) * flAmount );
		pModel->SetPoseParameter( "move_y", -sinf( flRel ) * flAmount );
		// Some classes' runs blend by direction and amount instead (the Bowman's Huntsman run,
		// Scout's bat run, ...): without these they slid (Alex, 2026-10-11).
		pModel->SetPoseParameter( "move_yaw", RAD2DEG( flRel ) );
		pModel->SetPoseParameter( "move_scale", flAmount );
	}
	else
	{
		pModel->SetPoseParameter( "move_x", 0.0f );
		pModel->SetPoseParameter( "move_y", 0.0f );
		pModel->SetPoseParameter( "move_scale", 0.0f );
	}
	const float flBodyYawPose = clamp( AngleNormalize( flHeadYaw - flBodyYaw ), -45.0f, 45.0f );
	static double s_flAimLogAt;
	if ( r.bAimingAtPlayer && Plat_FloatTime() >= s_flAimLogAt )
	{
		s_flAimLogAt = Plat_FloatTime() + 3.0;
		Msg( "FortCraft robot %d (kind %d): facing %.0f, to player %.0f, body_yaw %.0f, body_pitch %.0f, speed %.0f, seq %s\n",
			r.id, r.kind, flBodyYaw, flHeadYaw, flBodyYawPose, flAimPitch, flSpeed,
			pModel->GetModelPtr() ? pModel->GetModelPtr()->pSeqdesc( nSeq ).pszLabel() : "?" );
	}
	pModel->SetPoseParameter( "body_yaw", flBodyYawPose );
	pModel->SetPoseParameter( "body_pitch", clamp( flAimPitch, -89.0f, 89.0f ) );
	float flCycle = pModel->GetCycle() + dt * flRate * pModel->GetSequenceCycleRate( pModel->GetModelPtr(), nSeq );
	pModel->SetCycle( pModel->IsSequenceLooping( nSeq ) ? flCycle - floorf( flCycle ) : MIN( flCycle, 1.0f ) );

	// Gestures: an attack (mob swing, ravager ram, bow release, evoker casting), else a flinch.
	bool bAttack = rose( proto::kRobotSwing ) || rose( proto::kRobotRam );
	if ( r.kind == 1 && ( was & proto::kRobotCharging ) && !bCharging )
		bAttack = true;  // Bowman lets the arrow go
	if ( ( f & proto::kRobotCasting ) && Plat_FloatTime() >= r.flNextCast )
	{
		bAttack = true;
		r.flNextCast = Plat_FloatTime() + 1.0;
	}
	if ( bAttack )
	{
		pModel->m_nGestureSeq = RobotSequence( pModel, k.attack );
		pModel->m_flGestureCycle = 0.0f;
	}
	else if ( rose( proto::kRobotHurt ) && pModel->m_flGestureCycle >= 1.0f )
	{
		pModel->m_nGestureSeq = RobotSequence( pModel, ACT_MP_GESTURE_FLINCH_CHEST );
		pModel->m_flGestureCycle = 0.0f;
	}
	if ( ( f & proto::kRobotFiring ) && pModel->m_flGestureCycle >= 1.0f )
	{
		pModel->m_nGestureSeq = RobotSequence( pModel, k.attack );  // the minigun keeps firing
		pModel->m_flGestureCycle = 0.0f;
	}
	if ( pModel->m_nGestureSeq >= 0 && pModel->m_flGestureCycle < 1.0f )
		pModel->m_flGestureCycle += dt * pModel->GetSequenceCycleRate( pModel->GetModelPtr(), pModel->m_nGestureSeq );

	// Far ones behind Minecraft's blocks aren't drawn.
	C_BaseAnimating *parts[ 3 ] = { pModel, r.pWeapon, r.pExtra };
	for ( int i = 0; i < 3; ++i )
	{
		if ( !parts[ i ] )
			continue;
		if ( f & proto::kRobotHidden )
			parts[ i ]->AddEffects( EF_NODRAW );
		else
			parts[ i ]->RemoveEffects( EF_NODRAW );
	}

	// Sounds.
	if ( bGround && bMoving && r.kind != proto::kRobotKindTank )
	{
		r.flStepUnits += flSpeed * dt;
		const float flStride = ( bGiant ? 1.0f : 0.9f ) * UNITS_PER_BLOCK * MAX( flScale, 0.5f );
		if ( r.flStepUnits >= flStride )
		{
			r.flStepUnits = 0.0f;
			RobotSound( bGiant && k.pszGiantStep ? k.pszGiantStep : "MVM.BotStep", pos );
		}
	}
	const double flNow = Plat_FloatTime();

	// Demoknight's shield charge: Minecraft's server says when one starts.
	if ( r.kind == 2 && rose( proto::kRobotCharge ) )
	{
		RobotSound( "DemoCharge.Charging", pos );
		RobotVoice( k, bGiant, "BattleCry", k.nBattleCry, pos );
	}
	// Bowman's Huntsman.
	if ( r.kind == 1 && rose( proto::kRobotSwing ) )
		RobotSound( "Weapon_CompoundBow.Single", pos );
	// Round 4's robots: their weapon sound on each shot; Sniper and Scout shots trace to you.
	if ( rose( proto::kRobotSwing ) )
	{
		const bool bBoss = ( in.extra & proto::kRobotExBoss ) != 0;
		if ( r.kind == 11 )
			RobotSound( bGiant || bBoss ? "MVM.GiantSoldierRocketShoot" : "Weapon_RPG.Single", pos );
		else if ( r.kind == 12 )
			RobotSound( "Weapon_GrenadeLauncher.Single", pos );
		else if ( r.kind == 14 )
			RobotSound( "Weapon_Knife.Miss", pos );
		else if ( ( r.kind == 13 || r.kind == 15 ) && pLocal )
		{
			RobotSound( r.kind == 13 ? "Weapon_SniperRifle.Single" : "Weapon_Scatter_Gun.Single", pos );
			Vector vecMuzzle = pos + Vector( 0, 0, in.height * UNITS_PER_BLOCK * 0.7f );
			QAngle angMuzzle;
			const int iMuzzle = r.pWeapon ? r.pWeapon->LookupAttachment( "muzzle" ) : 0;
			if ( iMuzzle > 0 )
				r.pWeapon->GetAttachment( iMuzzle, vecMuzzle, angMuzzle );
			const int nPellets = r.kind == 15 ? 4 : 1;
			for ( int i = 0; i < nPellets; ++i )
			{
				const Vector vecEnd = pLocal->WorldSpaceCenter() + Vector( RandomFloat( -20, 20 ), RandomFloat( -20, 20 ), RandomFloat( -20, 20 ) );
				UTIL_ParticleTracer( "bullet_tracer01_blue", vecMuzzle, vecEnd );
			}
		}
	}
	// Uber starting.
	if ( bUber && !( r.extra & proto::kRobotExUber ) )
		RobotSound( "TFPlayer.InvulnerableOn", pos );
	// Spy: fades out while cloaked, back in to stab.
	if ( r.kind == 14 )
	{
		const bool bCloaked = ( in.extra & proto::kRobotExCloaked ) != 0;
		if ( bCloaked != ( ( r.extra & proto::kRobotExCloaked ) != 0 ) )
			RobotSound( bCloaked ? "Player.Spy_Cloak" : "Player.Spy_UnCloak", pos );
		r.flCloak = Approach( bCloaked ? 1.0f : 0.0f, r.flCloak, dt );
		C_BaseAnimating *spyParts[ 2 ] = { pModel, r.pWeapon };
		for ( int i = 0; i < 2; ++i )
		{
			if ( !spyParts[ i ] )
				continue;
			spyParts[ i ]->SetRenderMode( r.flCloak > 0.0f ? kRenderTransColor : kRenderNormal );
			spyParts[ i ]->SetRenderColorA( (byte)( 255.0f * ( 1.0f - r.flCloak ) ) );
			if ( r.flCloak >= 0.99f )
				spyParts[ i ]->AddEffects( EF_NODRAW );
		}
	}
	// Medic: the healing beam to its patient.
	if ( r.kind == 3 )
	{
		C_BaseAnimating *pPatient = NULL;
		if ( ( in.extra & proto::kRobotExHealing ) && in.target )
		{
			FOR_EACH_VEC( s_Robots, i )
			{
				if ( s_Robots[ i ].id == in.target && s_Robots[ i ].pModel && s_Robots[ i ].hModel.Get() == s_Robots[ i ].pModel && !s_Robots[ i ].bDead )
					pPatient = s_Robots[ i ].pModel;
			}
		}
		const int nWant = pPatient ? in.target : 0;
		if ( nWant != r.nBeamTarget || ( !pPatient && r.pBeam ) )
		{
			if ( r.pBeam && r.pWeapon )
				r.pWeapon->ParticleProp()->StopEmission( r.pBeam );
			r.pBeam = NULL;
			r.nBeamTarget = nWant;
			if ( pPatient && r.pWeapon )
			{
				r.pBeam = r.pWeapon->ParticleProp()->Create( "medicgun_beam_blue", PATTACH_POINT_FOLLOW, "muzzle" );
				if ( r.pBeam )
					r.pWeapon->ParticleProp()->AddControlPoint( r.pBeam, 1, pPatient, PATTACH_ABSORIGIN_FOLLOW, NULL, Vector( 0, 0, 50 ) );
			}
		}
	}
	// On fire, or wearing Jarate / Mad Milk: TF2's own effects on the robot (Alex: Jarate and
	// fire didn't seem to work on them; Minecraft's own fire and drips aren't drawn for robots).
	const bool bBurning = ( in.extra & proto::kRobotExBurning ) != 0;
	if ( bBurning && !r.pBurn )
		r.pBurn = pModel->ParticleProp()->Create( "burningplayer_blue", PATTACH_ABSORIGIN_FOLLOW );
	else if ( !bBurning && r.pBurn )
	{
		pModel->ParticleProp()->StopEmission( r.pBurn );
		r.pBurn = NULL;
	}
	const uint8_t nDrip = in.extra & ( proto::kRobotExJarated | proto::kRobotExMilked );
	if ( nDrip != ( r.extra & ( proto::kRobotExJarated | proto::kRobotExMilked ) ) )
	{
		if ( r.pDrips )
			pModel->ParticleProp()->StopEmission( r.pDrips );
		r.pDrips = NULL;
		if ( nDrip )
			r.pDrips = pModel->ParticleProp()->Create( ( nDrip & proto::kRobotExJarated ) ? "peejar_drips" : "peejar_drips_milk",
				PATTACH_ABSORIGIN_FOLLOW, INVALID_PARTICLE_ATTACHMENT, Vector( 0, 0, in.height * UNITS_PER_BLOCK * 0.7f ) );
	}
	r.extra = in.extra;
	// Giant Heavy's minigun: winds up while hunting; firing, the gun loop, muzzle flash and
	// tracers at the player.
	if ( r.kind == 5 )
	{
		if ( rose( proto::kRobotAggressive ) )
			RobotSound( "MVM.GiantHeavyGunWindUp", pos );
		if ( rose( proto::kRobotFiring ) )
		{
			if ( r.pWeapon && !r.pMuzzle )
				r.pMuzzle = r.pWeapon->ParticleProp()->Create( "muzzle_minigun_constant", PATTACH_POINT_FOLLOW, "muzzle" );
		}
		else if ( ( was & proto::kRobotFiring ) && !( f & proto::kRobotFiring ) )
		{
			RobotSound( "MVM.GiantHeavyGunWindDown", pos );
			if ( r.pWeapon && r.pMuzzle )
				r.pWeapon->ParticleProp()->StopEmission( r.pMuzzle );
			r.pMuzzle = NULL;
		}
		if ( ( f & proto::kRobotFiring ) && r.pWeapon && pLocal && flNow >= r.flNextTracer )
		{
			r.flNextTracer = flNow + 0.08;
			Vector vecMuzzle;
			QAngle angMuzzle;
			const int iMuzzle = r.pWeapon->LookupAttachment( "muzzle" );
			if ( iMuzzle <= 0 || !r.pWeapon->GetAttachment( iMuzzle, vecMuzzle, angMuzzle ) )
				vecMuzzle = pos + Vector( 0, 0, in.height * UNITS_PER_BLOCK * 0.6f );
			const Vector vecEnd = pLocal->WorldSpaceCenter() + Vector( RandomFloat( -24, 24 ), RandomFloat( -24, 24 ), RandomFloat( -24, 24 ) );
			UTIL_ParticleTracer( "bullet_tracer01_blue", vecMuzzle, vecEnd );
		}
	}
	if ( rose( proto::kRobotHurt ) )
	{
		RobotVoice( k, bGiant, "PainSharp", k.nPain, pos );
		r.flNextVoice = MAX( r.flNextVoice, flNow + 1.0 );
	}
	else if ( rose( proto::kRobotRoar ) )
	{
		RobotVoice( k, bGiant, "BattleCry", k.nBattleCry, pos );
	}
	else if ( rose( proto::kRobotCelebrating ) )
	{
		RobotVoice( k, bGiant, "Cheers", k.nCheers, pos );
	}
	else if ( ( f & proto::kRobotAggressive ) && flNow >= r.flNextVoice )
	{
		RobotVoice( k, bGiant, "BattleCry", k.nBattleCry, pos );
		r.flNextVoice = flNow + RandomFloat( 8.0f, 16.0f );
	}
	r.flags = f;
}

// ---- MvM wave panel during village raids (Alex, 2026-10-11) -----------------------------------
// Like MvM's: "WAVE 2 / 5", a bar of how much of the wave is left, and an icon with a count for
// each kind of robot still alive. Shown while a raid is under way, gone after.
static const char *const kRaidIcons[ 16 ] = {
	NULL, "hud/leaderboard_class_sniper_bow", "hud/leaderboard_class_demoknight", "hud/leaderboard_class_medic",
	"hud/leaderboard_class_scout_bat", "hud/leaderboard_class_heavy_giant", "hud/leaderboard_class_sniper_jarate", NULL,
	NULL, NULL, NULL, "hud/leaderboard_class_soldier", "hud/leaderboard_class_demo", "hud/leaderboard_class_sniper",
	"hud/leaderboard_class_spy", "hud/leaderboard_class_scout",
};

class CFortCraftRaidPanel : public vgui::Panel
{
	DECLARE_CLASS_SIMPLE( CFortCraftRaidPanel, vgui::Panel );
public:
	CFortCraftRaidPanel() : BaseClass( NULL, "FortCraftRaidPanel" )
	{
		SetParent( enginevgui->GetPanel( PANEL_CLIENTDLL ) );
		SetMouseInputEnabled( false );
		SetKeyBoardInputEnabled( false );
		SetPaintBackgroundEnabled( false );
		SetVisible( false );
		for ( int i = 0; i < 16; ++i )
			m_nIcon[ i ] = -1;
	}
	uint32_t m_nWave = 0, m_nTotal = 0, m_nAlive = 0, m_nWaveMax = 1;
	uint8_t m_nCounts[ 16 ] = {};

	virtual void Paint() OVERRIDE
	{
		int sw, sh;
		vgui::surface()->GetScreenSize( sw, sh );
		const float s = sh / 480.0f;
		int nKinds = 0;
		for ( int k = 1; k < 16; ++k )
			nKinds += ( kRaidIcons[ k ] && m_nCounts[ k ] ) ? 1 : 0;
		const int icon = (int)( 18 * s ), gap = (int)( 4 * s );
		const int w = MAX( (int)( 150 * s ), nKinds * ( icon + gap ) + gap * 3 ), h = (int)( 58 * s );
		SetBounds( ( sw - w ) / 2, (int)( 6 * s ), w, h );

		vgui::surface()->DrawSetColor( 40, 37, 34, 200 );
		vgui::surface()->DrawFilledRect( 0, 0, w, h );
		vgui::surface()->DrawSetColor( 117, 107, 94, 255 );
		vgui::surface()->DrawOutlinedRect( 0, 0, w, h );

		vgui::HScheme hScheme = vgui::scheme()->GetScheme( "ClientScheme" );
		vgui::IScheme *pScheme = vgui::scheme()->GetIScheme( hScheme );
		vgui::HFont font = pScheme ? pScheme->GetFont( "HudFontSmallBold", true ) : vgui::INVALID_FONT;
		wchar_t wszTitle[ 64 ];
		V_snwprintf( wszTitle, ARRAYSIZE( wszTitle ), L"WAVE %u / %u", m_nWave, m_nTotal );
		int tw = 0, th = 0;
		if ( font != vgui::INVALID_FONT )
		{
			vgui::surface()->GetTextSize( font, wszTitle, tw, th );
			vgui::surface()->DrawSetTextFont( font );
			vgui::surface()->DrawSetTextColor( 235, 226, 202, 255 );
			vgui::surface()->DrawSetTextPos( ( w - tw ) / 2, (int)( 3 * s ) );
			vgui::surface()->DrawPrintText( wszTitle, V_wcslen( wszTitle ) );
		}

		// How much of the wave is left.
		const int barX = gap * 2, barY = (int)( 17 * s ), barW = w - gap * 4, barH = (int)( 6 * s );
		vgui::surface()->DrawSetColor( 20, 20, 20, 220 );
		vgui::surface()->DrawFilledRect( barX, barY, barX + barW, barY + barH );
		const float flLeft = m_nWaveMax ? clamp( (float)m_nAlive / (float)m_nWaveMax, 0.0f, 1.0f ) : 0.0f;
		vgui::surface()->DrawSetColor( 133, 163, 191, 255 );
		vgui::surface()->DrawFilledRect( barX, barY, barX + (int)( barW * flLeft ), barY + barH );

		// Robots still alive, by kind.
		int x = ( w - nKinds * ( icon + gap ) + gap ) / 2;
		const int iconY = barY + barH + (int)( 4 * s );
		for ( int k = 1; k < 16; ++k )
		{
			if ( !kRaidIcons[ k ] || !m_nCounts[ k ] )
				continue;
			if ( m_nIcon[ k ] < 0 )
			{
				m_nIcon[ k ] = vgui::surface()->CreateNewTextureID();
				vgui::surface()->DrawSetTextureFile( m_nIcon[ k ], kRaidIcons[ k ], true, false );
			}
			vgui::surface()->DrawSetTexture( m_nIcon[ k ] );
			vgui::surface()->DrawSetColor( 255, 255, 255, 255 );
			vgui::surface()->DrawTexturedRect( x, iconY, x + icon, iconY + icon );
			if ( font != vgui::INVALID_FONT )
			{
				wchar_t wszCount[ 8 ];
				V_snwprintf( wszCount, ARRAYSIZE( wszCount ), L"%d", m_nCounts[ k ] );
				vgui::surface()->GetTextSize( font, wszCount, tw, th );
				vgui::surface()->DrawSetTextColor( 235, 226, 202, 255 );
				vgui::surface()->DrawSetTextPos( x + ( icon - tw ) / 2, iconY + icon + (int)( 1 * s ) );
				vgui::surface()->DrawPrintText( wszCount, V_wcslen( wszCount ) );
			}
			x += icon + gap;
		}
	}

private:
	int m_nIcon[ 16 ];
};
static CFortCraftRaidPanel *s_pRaidPanel;

bool FortCraft_RaidHudActive()
{
	return s_bLinked && s_pShm && At< proto::RaidState >( proto::kOffRaidState ).state == 1;
}

// Replaced by TF2's own MvM wave panel (FortCraft_RaidHudActive); kept for reference, unused.
static void UpdateRaidPanel()
{
	const proto::RaidState &raid = At< proto::RaidState >( proto::kOffRaidState );
	const bool bShow = s_bLinked && raid.state == 1 && raid.wave > 0;
	if ( !s_pRaidPanel )
	{
		if ( !bShow )
			return;
		s_pRaidPanel = new CFortCraftRaidPanel();
	}
	if ( bShow )
	{
		if ( raid.wave != s_pRaidPanel->m_nWave )
			s_pRaidPanel->m_nWaveMax = MAX( raid.alive, 1u );  // a new wave: its size
		s_pRaidPanel->m_nWaveMax = MAX( s_pRaidPanel->m_nWaveMax, raid.alive );
		s_pRaidPanel->m_nWave = raid.wave;
		s_pRaidPanel->m_nTotal = raid.totalWaves;
		s_pRaidPanel->m_nAlive = raid.alive;
		memcpy( s_pRaidPanel->m_nCounts, raid.kindCounts, sizeof( s_pRaidPanel->m_nCounts ) );
	}
	if ( s_pRaidPanel->IsVisible() != bShow )
		s_pRaidPanel->SetVisible( bShow );
}

static void UpdateRobots()
{
	static double s_flLast;
	const double flNow = Plat_FloatTime();
	const float dt = s_flLast > 0.0 ? (float)MIN( flNow - s_flLast, 0.1 ) : 0.0f;
	s_flLast = flNow;

	const proto::RaidRobots &src = At< proto::RaidRobots >( proto::kOffRaidRobots );
	const uint32_t seq = *(volatile const uint32_t *)&src.seq;
	if ( !( seq & 1 ) )
	{
		static proto::RaidRobot s_In[ proto::kMaxRaidRobots ];
		const uint32_t n = MIN( src.count, proto::kMaxRaidRobots );
		memcpy( s_In, src.robots, n * sizeof( proto::RaidRobot ) );
		if ( *(volatile const uint32_t *)&src.seq == seq )
		{
			FOR_EACH_VEC( s_Robots, i )
				s_Robots[ i ].bSeen = false;
			for ( uint32_t j = 0; j < n; ++j )
			{
				const proto::RaidRobot &in = s_In[ j ];
				if ( in.kind == 0 || in.kind >= ARRAYSIZE( kRobotKinds ) )
					continue;
				int found = -1;
				FOR_EACH_VEC( s_Robots, i )
				{
					if ( s_Robots[ i ].id == in.id && s_Robots[ i ].kind == in.kind )
					{
						found = i;
						break;
					}
				}
				if ( found < 0 )
				{
					if ( in.flags & proto::kRobotDead )
						continue;  // came into range already dying
					RobotState r;
					r.id = in.id;
					r.kind = in.kind;
					found = s_Robots.AddToTail( r );
				}
				s_Robots[ found ].bSeen = true;
				UpdateRobot( s_Robots[ found ], in, dt );
			}
			// Gone from Minecraft's list (out of range, despawned, or dead and removed).
			for ( int i = s_Robots.Count() - 1; i >= 0; --i )
			{
				if ( !s_Robots[ i ].bSeen )
				{
					const RobotState &gone = s_Robots[ i ];
					if ( gone.pModel && gone.hModel.Get() == gone.pModel )
					{
						if ( gone.kind == proto::kRobotKindJar )
						{
							// The jar broke: TF2's Jarate splash.
							DispatchParticleEffect( "peejar_impact", gone.vecLast, QAngle( 0, 0, 0 ) );
							RobotSound( "Jar.Explode", gone.vecLast );
						}
						else if ( gone.kind == proto::kRobotKindRocket || gone.kind == proto::kRobotKindPipe )
						{
							// A rocket or pipe went off.
							DispatchParticleEffect( "ExplosionCore_MidAir", gone.vecLast, QAngle( 0, 0, 0 ) );
							RobotSound( "BaseExplosionEffect.Sound", gone.vecLast );
						}
						else if ( gone.kind == proto::kRobotKindArrow )
						{
							RobotSound( "Weapon_Arrow.ImpactConcrete", gone.vecLast );
						}
					}
					ReleaseRobot( s_Robots[ i ] );
					s_Robots.Remove( i );
				}
			}
		}
	}
	UpdateRobotGibs( dt );

	// The Giant Heavy's minigun loop, while any live, drawn Giant Heavy fires (one loop for all).
	// An engine ambient loop, stopped for sure when the last one stops or dies.
	static const char *kMinigunLoop = "mvm/giant_heavy/giant_heavy_gunfire.wav";
	static bool s_bMinigun;
	bool bFiring = false;
	FOR_EACH_VEC( s_Robots, i )
	{
		const RobotState &r = s_Robots[ i ];
		if ( r.kind == 5 && !r.bDead && r.pModel && ( r.flags & proto::kRobotFiring ) )
			bFiring = true;
	}
	if ( bFiring != s_bMinigun )
	{
		s_bMinigun = bFiring;
		if ( bFiring )
		{
			enginesound->PrecacheSound( kMinigunLoop, true );
			enginesound->EmitAmbientSound( kMinigunLoop, 0.8f );
		}
		else
		{
			enginesound->EmitAmbientSound( kMinigunLoop, 0.0f, PITCH_NORM, SND_STOP );
		}
	}
}

// ---- Far objects behind Minecraft blocks (protocol FarObjects / FarHidden) -------------------
// Beyond the scanned blocks TF2 has no depth for Minecraft's world, so far objects drew through
// houses and hills. TF2 lists them; Minecraft answers which it can't see; TF2 hides those with
// EF_NODRAW on its own copy (re-applied each frame, since a network update can clear it).
static const float kFarObjectUnits = 10.0f * UNITS_PER_BLOCK;  // the depth boxes cover nearer
static CUtlVector< int > s_FarHiddenByUs;

static bool FarCandidate( C_BaseEntity *pEnt, C_BasePlayer *pLocal )
{
	if ( pEnt->IsDormant() || !pEnt->GetClientClass() || !pEnt->GetModel() || pEnt == pLocal )
		return false;
	if ( pEnt->entindex() < 0 )
		return false;  // TF2-side only (robots, gibs): no index for Minecraft to answer about
	if ( pEnt->GetMoveParent() == pLocal || pEnt->IsBaseCombatWeapon() )
		return false;  // what the player holds or wears
	const char *pszClass = pEnt->GetClientClass()->m_pNetworkName;
	if ( V_stristr( pszClass, "ViewModel" ) || V_stristr( pszClass, "Prop" ) || V_stristr( pszClass, "World" ) )
		return false;  // TF2's map pieces stay hidden by HideMapEntities
	return modelinfo->GetModelType( pEnt->GetModel() ) != mod_brush;
}

// From Minecraft (v45): TF2 entity index -> TF2 height (z) of the Minecraft water surface there.
static CUtlMap< int, float > s_WaterLines( DefLessFunc( int ) );
static void ReadWaterLines()
{
	const proto::WaterLines &w = At< proto::WaterLines >( proto::kOffWaterLines );
	const uint32_t seq = *(volatile const uint32_t *)&w.seq;
	if ( seq & 1 )
		return;  // mid-write: keep last frame's
	int ents[ proto::kMaxWaterLines ];
	float ys[ proto::kMaxWaterLines ];
	const uint32_t n = MIN( w.count, proto::kMaxWaterLines );
	for ( uint32_t i = 0; i < n; ++i )
	{
		ents[ i ] = w.list[ i ].ent;
		ys[ i ] = w.list[ i ].surfaceY;
	}
	if ( *(volatile const uint32_t *)&w.seq != seq )
		return;
	s_WaterLines.RemoveAll();
	for ( uint32_t i = 0; i < n; ++i )
		s_WaterLines.InsertOrReplace( ents[ i ], ( ys[ i ] - (float)MC_FLOOR_Y ) * UNITS_PER_BLOCK + s_vecOrigin.z );
}

static void UpdateFarOcclusion( C_TFPlayer *pPlayer )
{
	const Vector eye = pPlayer->EyePosition();
	struct Candidate { C_BaseEntity *pEnt; float flDist; };
	CUtlVector< Candidate > found;
	for ( C_BaseEntity *pEnt = ClientEntityList().FirstBaseEntity(); pEnt; pEnt = ClientEntityList().NextBaseEntity( pEnt ) )
	{
		if ( !FarCandidate( pEnt, pPlayer ) )
			continue;
		// All of them (v44): Minecraft also says which are in its water. It only hides those
		// beyond kFarObjectUnits; nearer ones are covered by the depth boxes.
		Candidate c = { pEnt, ( pEnt->WorldSpaceCenter() - eye ).Length() };
		found.AddToTail( c );
	}
	// Our own player too, for its water line when it's seen (taunts, third person). Never hidden:
	// the hiding below skips it.
	if ( pPlayer->GetModel() )
	{
		Candidate c = { pPlayer, 0.0f };
		found.AddToTail( c );
	}
	// Nearest first, if there are more than fit.
	for ( int i = 1; i < found.Count(); ++i )
		for ( int j = i; j > 0 && found[ j ].flDist < found[ j - 1 ].flDist; --j )
		{
			Candidate t = found[ j ];
			found[ j ] = found[ j - 1 ];
			found[ j - 1 ] = t;
		}

	proto::FarObjects &q = At< proto::FarObjects >( proto::kOffFarObjects );
	uint32_t seq = q.seq;
	*(volatile uint32_t *)&q.seq = ( seq + 1 ) | 1;  // odd: writing
	uint32_t n = 0;
	for ( int i = 0; i < found.Count() && n < proto::kMaxFarObjects; ++i )
	{
		proto::FarObject &o = q.list[ n++ ];
		o.ent = found[ i ].pEnt->entindex();
		ToMinecraft( found[ i ].pEnt->WorldSpaceCenter(), o.x, o.y, o.z );
		o.radius = MAX( 0.2f, found[ i ].pEnt->CollisionProp()->BoundingRadius() / UNITS_PER_BLOCK );
	}
	q.count = n;
	*(volatile uint32_t *)&q.seq = ( ( seq + 1 ) | 1 ) + 1;  // even: done

	// Minecraft's answer: which of them it can't see.
	const proto::FarHidden &a = At< proto::FarHidden >( proto::kOffFarHidden );
	const uint32_t aseq = *(volatile const uint32_t *)&a.seq;
	if ( aseq & 1 )
		return;
	ReadWaterLines();
	int hidden[ proto::kMaxFarObjects ];
	const uint32_t count = MIN( a.count, proto::kMaxFarObjects );
	for ( uint32_t i = 0; i < count; ++i )
		hidden[ i ] = a.ent[ i ];
	if ( *(volatile const uint32_t *)&a.seq != aseq )
		return;
	for ( int i = s_FarHiddenByUs.Count() - 1; i >= 0; --i )
	{
		bool bStill = false;
		for ( uint32_t k = 0; k < count && !bStill; ++k )
			bStill = hidden[ k ] == s_FarHiddenByUs[ i ];
		if ( bStill )
			continue;
		C_BaseEntity *pEnt = ClientEntityList().GetBaseEntity( s_FarHiddenByUs[ i ] );
		if ( pEnt )
			pEnt->RemoveEffects( EF_NODRAW );
		s_FarHiddenByUs.Remove( i );
	}
	for ( uint32_t k = 0; k < count; ++k )
	{
		C_BaseEntity *pEnt = ClientEntityList().GetBaseEntity( hidden[ k ] );
		if ( !pEnt || !FarCandidate( pEnt, pPlayer ) )
			continue;
		if ( !pEnt->IsEffectActive( EF_NODRAW ) )
			pEnt->AddEffects( EF_NODRAW );
		if ( s_FarHiddenByUs.Find( hidden[ k ] ) < 0 )
			s_FarHiddenByUs.AddToTail( hidden[ k ] );
	}
	static double s_flNextLog;
	if ( Plat_FloatTime() > s_flNextLog && ( n > 0 || s_FarHiddenByUs.Count() > 0 ) )
	{
		s_flNextLog = Plat_FloatTime() + 5.0;
		PerfLog( "FortCraft objects: %u listed, %d hidden behind Minecraft blocks, %u in Minecraft water\n", n, s_FarHiddenByUs.Count(), s_WaterLines.Count() );
		// Whether the no-shine settings took (both were silently ignored once).
		ConVarRef specular( "mat_specular" ), rimlight( "r_rimlight" );
		PerfLog( "FortCraft shine: mat_specular %s, r_rimlight %s\n", specular.IsValid() ? specular.GetString() : "missing",
			rimlight.IsValid() ? rimlight.GetString() : "missing" );
	}
}

// TF2's picture is laid over Minecraft's, so Minecraft's water never covered TF2 objects in it:
// a sticky on a lake bed or a player wading looked dry. Asked by the SDK's model drawing
// (c_baseanimating.cpp) for each model: the TF2 height of the Minecraft water surface at it, if
// it reaches into water. Hats, weapons and other attachments use their wearer's.
bool FortCraft_WaterLine( C_BaseEntity *pEnt, float &flSurfaceZ )
{
	if ( !pEnt || !s_bLinked || s_WaterLines.Count() == 0 )
		return false;
	C_BaseEntity *pRoot = pEnt->GetRootMoveParent();
	if ( !pRoot )
		return false;
	C_BaseAnimating *pAnim = pEnt->GetBaseAnimating(), *pRootAnim = pRoot->GetBaseAnimating();
	if ( ( pAnim && pAnim->IsViewModel() ) || ( pRootAnim && pRootAnim->IsViewModel() ) )
		return false;  // the first-person weapon never
	unsigned short i = s_WaterLines.Find( pRoot->entindex() );
	if ( i == s_WaterLines.InvalidIndex() )
		return false;
	flSurfaceZ = s_WaterLines[ i ];
	return true;
}

// Projectiles in flight, so Minecraft can show them.
static void WriteProjectiles()
{
	proto::Projectiles &out = At< proto::Projectiles >( proto::kOffProjectiles );
	uint32_t seq = out.seq;
	*(volatile uint32_t *)&out.seq = ( seq + 1 ) | 1;  // odd: writing
	uint32_t n = 0;
	for ( C_BaseEntity *pEnt = ClientEntityList().FirstBaseEntity(); pEnt && n < proto::kMaxProjectiles; pEnt = ClientEntityList().NextBaseEntity( pEnt ) )
	{
		if ( pEnt->IsDormant() || !pEnt->GetClientClass() )
			continue;
		const char *pszClass = pEnt->GetClientClass()->m_pNetworkName;
		uint32_t kind;
		if ( V_stristr( pszClass, "Rocket" ) )
			kind = proto::kProjRocket;
		else if ( V_stristr( pszClass, "Pipebomb" ) || V_stristr( pszClass, "Grenade" ) )
			kind = proto::kProjGrenade;
		else if ( V_stristr( pszClass, "Projectile" ) )
			kind = proto::kProjOther;
		else
			continue;
		proto::Projectile &p = out.list[ n++ ];
		p.id = (uint32_t)pEnt->entindex();
		p.kind = kind;
		ToMinecraft( pEnt->GetAbsOrigin(), p.x, p.y, p.z );
	}
	out.count = n;
	*(volatile uint32_t *)&out.seq = ( ( seq + 1 ) | 1 ) + 1;  // even: done
}

// ---- Backpack phase A: Minecraft's stacks on TF2's last backpack page -----------------------
// Each Minecraft item type becomes one local-only TF2 item (a renamed Scrap Metal with
// Minecraft's texture as its icon). Only this client's item list holds them: they are not in
// Steam's cache, so they are never sent to Steam or to TF2's server. Item ids live in a range
// no Steam item uses. A type keeps its id and backpack slot for the session.
static const itemid_t kLocalItemBase = 0xFC00000000000000ull;
static const item_definition_index_t kLocalItemDef = 5000;  // Scrap Metal: a plain craft item

struct LocalStack
{
	char        id[64];
	char        name[64];
	char        icon[64];
	uint32_t    count;
	int         pos;     // backpack position (1-based), 0 = none yet
	CEconItem  *pItem;     // NULL while not in the backpack
	CEconItem  *pPreview;  // crafting screen's result tile (kPreviewItemBase + n), never in the backpack
	char        previewName[64];
};
static CUtlVector< LocalStack * > s_LocalStacks;  // index n has item id kLocalItemBase + n
static const itemid_t kPreviewItemBase = 0xFD00000000000000ull;
static uint32_t s_nBackpackSeq = 0xFFFFFFFF;

// Both ranges: backpack items (kLocalItemBase + n) and crafting previews (kPreviewItemBase + n).
static LocalStack *LocalStackFor( unsigned long long itemID )
{
	const unsigned long long range = itemID & 0xFF00000000000000ull;
	if ( range != kLocalItemBase && range != kPreviewItemBase )
		return NULL;
	unsigned long long n = itemID - range;
	return n < (unsigned long long)s_LocalStacks.Count() ? s_LocalStacks[ (int)n ] : NULL;
}

CEconItem *FortCraft_FindLocalItem( unsigned long long itemID )
{
	LocalStack *p = LocalStackFor( itemID );
	if ( !p )
		return NULL;
	return ( itemID & 0xFF00000000000000ull ) == kPreviewItemBase ? p->pPreview : p->pItem;
}

// The registry entry for a Minecraft item id, made if new (not in the backpack yet).
static int LocalStackIndex( const char *pszId )
{
	for ( int n = 0; n < s_LocalStacks.Count(); ++n )
	{
		if ( !V_strcmp( s_LocalStacks[ n ]->id, pszId ) )
			return n;
	}
	LocalStack *pNew = new LocalStack;
	V_memset( pNew, 0, sizeof( *pNew ) );
	V_strncpy( pNew->id, pszId, sizeof( pNew->id ) );
	return s_LocalStacks.AddToTail( pNew );
}

const char *FortCraft_LocalItemImage( unsigned long long itemID )
{
	LocalStack *p = LocalStackFor( itemID );
	return p && p->icon[0] ? p->icon : NULL;
}

// First free backpack slot for a Minecraft stack: no Steam item and no other Minecraft stack
// there. The last page first; if the player's own items fill it, the pages before it, last to
// first (GitHub issue 13: some Minecraft items, like netherrack, never showed in TF2's backpack).
static int FreeLastPagePosition( CPlayerInventory *pInv )
{
	const int nPerPage = 50;  // BACKPACK_SLOTS_PER_PAGE
	const int nMax = pInv->GetMaxItemCount();
	const int nLastFirst = ( ( nMax + nPerPage - 1 ) / nPerPage - 1 ) * nPerPage + 1;
	for ( int nFirst = nLastFirst; nFirst >= 1; nFirst -= nPerPage )
	for ( int pos = nFirst; pos < nFirst + nPerPage && pos <= nMax; ++pos )
	{
		bool bUsed = false;
		for ( int i = 0; i < pInv->GetItemCount() && !bUsed; ++i )
			bUsed = ExtractBackpackPositionFromBackend( pInv->GetItem( i )->GetInventoryPosition() ) == (uint32)pos;
		// Only Minecraft items you carry now hold their slot here (pItem set).
		for ( int i = 0; i < s_LocalStacks.Count() && !bUsed; ++i )
			bUsed = s_LocalStacks[ i ]->pos == pos && s_LocalStacks[ i ]->pItem;
		if ( !bUsed )
		{
			// A slot kept for an item you no longer carry goes to this one; that item gets a new
			// slot if it comes back. Kept slots used to pile up over a session until the page was
			// full of them and new items showed nowhere (Alex's testers, 2026-10-13).
			for ( int i = 0; i < s_LocalStacks.Count(); ++i )
			{
				if ( s_LocalStacks[ i ]->pos == pos )
					s_LocalStacks[ i ]->pos = 0;
			}
			return pos;
		}
	}
	return 0;
}

static void RemoveLocalItem( CPlayerInventory *pInv, int n )
{
	LocalStack &st = *s_LocalStacks[ n ];
	if ( !st.pItem )
		return;
	CEconItem *pOld = st.pItem;
	st.pItem = NULL;  // GetSOCDataForItem stops finding it before the list drops it
	pInv->FortCraft_RemoveLocalItem( kLocalItemBase + n );
	delete pOld;
}

static void AddLocalItem( CPlayerInventory *pInv, int n )
{
	LocalStack &st = *s_LocalStacks[ n ];
	if ( !st.pos )
		st.pos = FreeLastPagePosition( pInv );
	if ( !st.pos )
	{
		Msg( "FortCraft backpack: no free backpack slot for %s\n", st.id );
		return;
	}
	CEconItem *pItem = new CEconItem;
	pItem->SetItemID( kLocalItemBase + n );
	pItem->SetOriginalID( kLocalItemBase + n );
	pItem->SetAccountID( pInv->GetOwner().GetAccountID() );
	pItem->SetDefinitionIndex( kLocalItemDef );
	pItem->SetQuality( AE_UNIQUE );
	pItem->SetItemLevel( 1 );
	pItem->SetQuantity( 1 );
	pItem->SetInventoryToken( kBackendPosition_NewFormat | (uint32)st.pos );
	pItem->SetCustomName( CFmtStr( "%s x%u", st.name, st.count ) );
	st.pItem = pItem;  // before adding: the list looks it up while adding
	if ( !pInv->FortCraft_AddLocalItem( pItem ) )
	{
		st.pItem = NULL;
		delete pItem;
		Msg( "FortCraft backpack: TF2 refused the item for %s\n", st.id );
	}
}

static void SyncBackpack()
{
	const proto::Backpack &shared = At< proto::Backpack >( proto::kOffBackpack );
	uint32_t seq = *(volatile const uint32_t *)&shared.seq;
	if ( ( seq & 1 ) || seq == s_nBackpackSeq )
		return;
	CPlayerInventory *pInv = TFInventoryManager() ? TFInventoryManager()->GetLocalTFInventory() : NULL;
	if ( !pInv || !pInv->RetrievedInventoryFromSteam() || pInv->GetMaxItemCount() <= 0 )
		return;  // try again next frame, once TF2 has the real backpack
	static proto::Backpack s_Copy;  // ~10 KB: not on the stack
	V_memcpy( &s_Copy, &shared, sizeof( s_Copy ) );
	if ( *(volatile const uint32_t *)&shared.seq != seq )
		return;  // Minecraft wrote meanwhile; next frame
	s_nBackpackSeq = seq;

	CUtlVector< bool > present;
	present.SetCount( s_LocalStacks.Count() );
	for ( int i = 0; i < present.Count(); ++i )
		present[ i ] = false;
	const uint32_t nCount = MIN( s_Copy.count, proto::kMaxBackpackStacks );
	int nChanged = 0;
	for ( uint32_t k = 0; k < nCount; ++k )
	{
		proto::BackpackStack in = s_Copy.stacks[ k ];
		in.id[ 63 ] = in.name[ 63 ] = in.icon[ 63 ] = 0;
		const int n = LocalStackIndex( in.id );
		while ( present.Count() < s_LocalStacks.Count() )
			present.AddToTail( false );
		present[ n ] = true;
		LocalStack &st = *s_LocalStacks[ n ];
		if ( st.pItem && st.count == in.count && !V_strcmp( st.name, in.name ) && !V_strcmp( st.icon, in.icon ) )
			continue;
		RemoveLocalItem( pInv, n );
		V_strncpy( st.name, in.name, sizeof( st.name ) );
		V_strncpy( st.icon, in.icon, sizeof( st.icon ) );
		st.count = in.count;
		AddLocalItem( pInv, n );
		++nChanged;
	}
	for ( int n = 0; n < s_LocalStacks.Count(); ++n )
	{
		if ( !present[ n ] && s_LocalStacks[ n ]->pItem )
		{
			RemoveLocalItem( pInv, n );  // keeps its slot, in case it comes back
			++nChanged;
		}
	}
	if ( nChanged )
		Msg( "FortCraft backpack: %u Minecraft item types, %d changed\n", nCount, nChanged );
}

// ---- Backpack phase B: "Equip to hand" ------------------------------------------------------
// The backpack asks Minecraft to hold a Minecraft item; Minecraft moves it into the hand and
// draws it there, and reports that back. While it holds one, TF2 hides its own weapon model
// (Minecraft also switches TF2 to melee, whose swing breaks the aimed block).
bool FortCraft_IsLocalItem( unsigned long long itemID )
{
	return LocalStackFor( itemID ) != NULL;
}

// FortCraft's supply packs are used straight from the backpack ("Use"), never held: they have
// no hand model (Alex saw a missing-texture block).
static bool IsSupplyPack( const char *pszId )
{
	return V_strncmp( pszId, "fortcraft:", 10 ) == 0 && V_stristr( pszId, "_pack" ) != NULL;
}

const char *FortCraft_LocalItemMenuLabel( unsigned long long itemID )
{
	LocalStack *p = LocalStackFor( itemID );
	return p && IsSupplyPack( p->id ) ? "Use" : "Equip to hand";
}

void FortCraft_EquipLocalItem( unsigned long long itemID )
{
	LocalStack *p = LocalStackFor( itemID );
	if ( !s_pShm || !p )
		return;
	proto::HandRequest &req = At< proto::HandRequest >( proto::kOffHandRequest );
	if ( IsSupplyPack( p->id ) )
	{
		V_snprintf( req.id, sizeof( req.id ), "use:%s", p->id );  // TF2's server plays the pickup sound
		Msg( "FortCraft hand: asked Minecraft to use %s\n", p->id );
	}
	else
	{
		V_strncpy( req.id, p->id, sizeof( req.id ) );
		if ( vgui::surface() )
			vgui::surface()->PlaySound( "ui/item_default_pickup.wav" );
		Msg( "FortCraft hand: asked Minecraft to hold %s\n", p->id );
	}
	*(volatile uint32_t *)&req.count = req.count + 1;
}

// ---- The Minecraft item key (Alex, 2026-10-11) -----------------------------------------------
// Each class can have one Minecraft item on a number key: 4, 6 for the Engineer (whose 4 and 5 are
// PDAs) and 5 for the Spy (whose 4 is the disguise kit). Set from the backpack ("Put on key 4"); pressing the key pulls the item out
// as "Equip to hand" does (food to eat, blocks, tools, potions...), and running out or a weapon
// key goes back to the weapon you had. Saved in cfg/fortcraft_quickslots.txt.
static KeyValues *s_pQuickSlots;

static KeyValues *QuickSlots()
{
	if ( !s_pQuickSlots )
	{
		s_pQuickSlots = new KeyValues( "fortcraft_quickslots" );
		s_pQuickSlots->LoadFromFile( g_pFullFileSystem, "cfg/fortcraft_quickslots.txt", "MOD" );
	}
	return s_pQuickSlots;
}

// 0-based. Key 4; Engineer 6 (4 and 5 are his PDAs); Spy 5 (4 is his disguise kit). Spy was on 6
// at first, but Alex expected 5, his first free key (2026-10-12).
static int QuickSlotIndex( int nClass )
{
	return nClass == TF_CLASS_ENGINEER ? 5 : nClass == TF_CLASS_SPY ? 4 : 3;
}

static int LocalClass()
{
	C_TFPlayer *pPlayer = C_TFPlayer::GetLocalTFPlayer();
	return pPlayer && pPlayer->GetPlayerClass() ? pPlayer->GetPlayerClass()->GetClassIndex() : TF_CLASS_UNDEFINED;
}

// One item for every class (Alex, 2026-10-12: it should come along when you switch class, until
// you change it). Saved as "all"; the older per-class entries are only read until then.
static const char *QuickItemFor( int nClass )
{
	const char *pszAll = QuickSlots()->GetString( "all", NULL );
	return pszAll ? pszAll : QuickSlots()->GetString( VarArgs( "%d", nClass ), "" );
}

static void SetQuickItem( const char *pszId )
{
	QuickSlots()->SetString( "all", pszId );
	QuickSlots()->SaveToFile( g_pFullFileSystem, "cfg/fortcraft_quickslots.txt", "MOD" );
}

const char *FortCraft_QuickSlotMenuLabel( unsigned long long itemID )
{
	LocalStack *p = LocalStackFor( itemID );
	const int nClass = LocalClass();
	const int nKey = QuickSlotIndex( nClass ) + 1;
	static char s_szLabel[ 64 ];
	const bool bOn = p && !V_strcmp( QuickItemFor( nClass ), p->id );
	V_snprintf( s_szLabel, sizeof( s_szLabel ), bOn ? "Take off key %d" : "Put on key %d", nKey );
	return s_szLabel;
}

void FortCraft_ToggleQuickSlot( unsigned long long itemID )
{
	LocalStack *p = LocalStackFor( itemID );
	const int nClass = LocalClass();
	if ( !p || nClass == TF_CLASS_UNDEFINED )
		return;
	const bool bOn = !V_strcmp( QuickItemFor( nClass ), p->id );
	SetQuickItem( bOn ? "" : p->id );
	if ( vgui::surface() )
		vgui::surface()->PlaySound( "ui/item_default_pickup.wav" );
	Msg( "FortCraft quick key: class %d key %d %s %s\n", nClass, QuickSlotIndex( nClass ) + 1, bOn ? "cleared" : "holds", p->id );
}

int FortCraft_QuickSlotKey( int nClass )
{
	return s_bLinked && nClass != TF_CLASS_UNDEFINED ? QuickSlotIndex( nClass ) + 1 : 0;
}

static int QuickStackIndex( int nClass )
{
	const char *pszId = QuickItemFor( nClass );
	for ( int n = 0; pszId[ 0 ] && n < s_LocalStacks.Count(); ++n )
	{
		if ( !V_strcmp( s_LocalStacks[ n ]->id, pszId ) )
			return n;
	}
	return -1;
}

CEconItemView *FortCraft_QuickSlotItem( int nClass )
{
	const int n = QuickStackIndex( nClass );
	CPlayerInventory *pInv = TFInventoryManager() ? TFInventoryManager()->GetLocalTFInventory() : NULL;
	if ( n < 0 || !pInv || !s_LocalStacks[ n ]->pItem )
		return NULL;
	return pInv->GetInventoryItemByItemID( kLocalItemBase + n );
}

const char *FortCraft_QuickSlotName( int nClass )
{
	const int n = QuickStackIndex( nClass );
	if ( n >= 0 && s_LocalStacks[ n ]->name[ 0 ] )
		return s_LocalStacks[ n ]->name;
	return QuickItemFor( nClass );
}

int FortCraft_QuickSlotChoiceCount()
{
	return s_LocalStacks.Count();
}

const char *FortCraft_QuickSlotChoice( int n )
{
	if ( n < 0 || n >= s_LocalStacks.Count() || !s_LocalStacks[ n ]->pItem || IsSupplyPack( s_LocalStacks[ n ]->id ) )
		return NULL;
	return s_LocalStacks[ n ]->name[ 0 ] ? s_LocalStacks[ n ]->name : s_LocalStacks[ n ]->id;
}

CEconItemView *FortCraft_QuickSlotChoiceItem( int n )
{
	CPlayerInventory *pInv = TFInventoryManager() ? TFInventoryManager()->GetLocalTFInventory() : NULL;
	if ( !pInv || !FortCraft_QuickSlotChoice( n ) )
		return NULL;
	return pInv->GetInventoryItemByItemID( kLocalItemBase + n );
}

void FortCraft_SetQuickSlotItem( int nClass, unsigned long long itemID )
{
	const bool bLocal = ( itemID & 0xFF00000000000000ull ) == kLocalItemBase && LocalStackFor( itemID );
	FortCraft_SetQuickSlot( nClass, bLocal ? (int)( itemID - kLocalItemBase ) : -1 );
}

void FortCraft_SetQuickSlot( int nClass, int n )
{
	if ( nClass == TF_CLASS_UNDEFINED || ( n >= 0 && !FortCraft_QuickSlotChoice( n ) ) )
		return;
	SetQuickItem( n >= 0 ? s_LocalStacks[ n ]->id : "" );
	if ( vgui::surface() )
		vgui::surface()->PlaySound( "ui/item_default_pickup.wav" );
	Msg( "FortCraft quick key: class %d key %d %s %s (loadout screen)\n", nClass, QuickSlotIndex( nClass ) + 1,
		n >= 0 ? "holds" : "cleared", n >= 0 ? s_LocalStacks[ n ]->id : "" );
}

static bool QuickSlotHasItem( int nClass, int nSlot )
{
	return nClass > TF_CLASS_UNDEFINED && nSlot == QuickSlotIndex( nClass ) && QuickItemFor( nClass )[ 0 ];
}

// A number key for an empty weapon slot: if it's this class's Minecraft item key, pull it out.
static void QuickSlotPressed( int nSlot )
{
	const int nClass = LocalClass();
	if ( nClass == TF_CLASS_UNDEFINED || nSlot != QuickSlotIndex( nClass ) || !s_pShm )
		return;
	const char *pszId = QuickItemFor( nClass );
	if ( !pszId[ 0 ] )
		return;
	proto::HandRequest &req = At< proto::HandRequest >( proto::kOffHandRequest );
	V_snprintf( req.id, sizeof( req.id ), "quick:%s", pszId );
	*(volatile uint32_t *)&req.count = req.count + 1;
	Msg( "FortCraft quick key: pulling out %s\n", pszId );
}

static void SyncHand()
{
	const proto::HandState &state = At< proto::HandState >( proto::kOffHandState );
	static int s_nHolding = -1;  // -1: not set yet this run (r_drawviewmodel is saved in config)
	int nHolding = *(volatile const uint32_t *)&state.equipped == 1 ? 1 : 0;
	if ( nHolding == s_nHolding )
		return;
	s_nHolding = nHolding;
	const bool bHolding = nHolding == 1;
	char id[ 64 ];
	V_strncpy( id, state.id, sizeof( id ) );
	Msg( "FortCraft hand: Minecraft %s %s\n", bHolding ? "holds" : "put away", bHolding ? id : "its item" );
	engine->ClientCmd_Unrestricted( bHolding ? "r_drawviewmodel 0\n" : "r_drawviewmodel 1\n" );
}

// ---- Backpack phase C: Minecraft's 2x2 crafting in TF2's crafting screen ---------------------
// Minecraft lists the recipes the inventory can make; the crafting screen shows them and asks
// Minecraft to craft one. The list is copied whole whenever Minecraft changes it.
static proto::CraftRecipes s_Recipes;  // last complete copy (~16 KB)
static uint32_t s_nRecipesSeq;

static void RefreshRecipes()
{
	if ( !s_pShm || !s_bLinked )
	{
		s_Recipes.count = 0;
		return;
	}
	const proto::CraftRecipes &shared = At< proto::CraftRecipes >( proto::kOffRecipes );
	uint32_t seq = *(volatile const uint32_t *)&shared.seq;
	if ( ( seq & 1 ) || seq == s_nRecipesSeq )
		return;
	static proto::CraftRecipes s_Copy;
	V_memcpy( &s_Copy, &shared, sizeof( s_Copy ) );
	if ( *(volatile const uint32_t *)&shared.seq != seq )
		return;
	s_nRecipesSeq = seq;
	V_memcpy( &s_Recipes, &s_Copy, sizeof( s_Recipes ) );
	s_Recipes.count = MIN( s_Recipes.count, proto::kMaxRecipes );
	for ( uint32_t i = 0; i < s_Recipes.count; ++i )
	{
		s_Recipes.recipes[ i ].key[ 63 ] = s_Recipes.recipes[ i ].name[ 63 ] = 0;
		proto::CraftRecipe &r = s_Recipes.recipes[ i ];
		r.inputs[ 127 ] = r.resultId[ 63 ] = r.resultIcon[ 63 ] = 0;
		for ( int k = 0; k < 4; ++k )
			r.ingredients[ k ][ 63 ] = 0;
	}
}

unsigned int FortCraft_RecipesVersion()
{
	RefreshRecipes();
	return s_nRecipesSeq;
}

int FortCraft_RecipeCount()
{
	RefreshRecipes();
	return (int)s_Recipes.count;
}

const char *FortCraft_RecipeName( int i )
{
	return i >= 0 && i < (int)s_Recipes.count ? s_Recipes.recipes[ i ].name : "";
}

const char *FortCraft_RecipeInputs( int i )
{
	return i >= 0 && i < (int)s_Recipes.count ? s_Recipes.recipes[ i ].inputs : "";
}

unsigned long long FortCraft_RecipeIngredientItem( int i, int k )
{
	if ( i < 0 || i >= (int)s_Recipes.count || k < 0 || k >= 4 || !s_Recipes.recipes[ i ].ingredients[ k ][ 0 ] )
		return 0;
	char id[ 64 ];
	V_strncpy( id, s_Recipes.recipes[ i ].ingredients[ k ], sizeof( id ) );
	const int n = LocalStackIndex( id );
	return s_LocalStacks[ n ]->pItem ? kLocalItemBase + n : 0;  // only if it's in the backpack
}

unsigned long long FortCraft_RecipeResultItem( int i )
{
	if ( i < 0 || i >= (int)s_Recipes.count || !s_Recipes.recipes[ i ].resultId[ 0 ] )
		return 0;
	CPlayerInventory *pInv = TFInventoryManager() ? TFInventoryManager()->GetLocalTFInventory() : NULL;
	if ( !pInv )
		return 0;
	const proto::CraftRecipe &r = s_Recipes.recipes[ i ];
	char id[ 64 ];
	V_strncpy( id, r.resultId, sizeof( id ) );
	const int n = LocalStackIndex( id );
	LocalStack &st = *s_LocalStacks[ n ];
	if ( r.resultIcon[ 0 ] && !st.icon[ 0 ] )
		V_strncpy( st.icon, r.resultIcon, sizeof( st.icon ) );
	if ( !st.pPreview || V_strcmp( st.previewName, r.name ) )
	{
		CEconItem *pItem = st.pPreview ? st.pPreview : new CEconItem;
		pItem->SetItemID( kPreviewItemBase + n );
		pItem->SetOriginalID( kPreviewItemBase + n );
		pItem->SetAccountID( pInv->GetOwner().GetAccountID() );
		pItem->SetDefinitionIndex( kLocalItemDef );
		pItem->SetQuality( AE_UNIQUE );
		pItem->SetItemLevel( 1 );
		pItem->SetQuantity( 1 );
		pItem->SetCustomName( r.name );
		V_strncpy( st.previewName, r.name, sizeof( st.previewName ) );
		st.pPreview = pItem;
	}
	return kPreviewItemBase + n;
}

unsigned int FortCraft_CraftResults( bool *pbLastOk )
{
	if ( !s_pShm )
		return 0;
	const proto::CraftResult &res = At< proto::CraftResult >( proto::kOffCraftResult );
	unsigned int count = *(volatile const uint32_t *)&res.count;
	if ( pbLastOk )
		*pbLastOk = res.ok == 1;
	return count;
}

void FortCraft_CraftRecipe( int i )
{
	if ( !s_pShm || i < 0 || i >= (int)s_Recipes.count )
		return;
	proto::CraftRequest &req = At< proto::CraftRequest >( proto::kOffCraftRequest );
	V_strncpy( req.key, s_Recipes.recipes[ i ].key, sizeof( req.key ) );
	*(volatile uint32_t *)&req.count = req.count + 1;
	Msg( "FortCraft craft: asked Minecraft to craft %s (%s)\n", s_Recipes.recipes[ i ].name, s_Recipes.recipes[ i ].key );
}

// TF2 attacks that hurt a Minecraft mob: TF2's hit sound and floating damage number there.
static void PollMobHits()
{
	const proto::MobHits &hits = At< proto::MobHits >( proto::kOffMobHits );
	static uint32_t s_nSeen = 0xFFFFFFFF;
	uint32_t count = *(volatile const uint32_t *)&hits.count;
	if ( s_nSeen == 0xFFFFFFFF || count < s_nSeen || count - s_nSeen > proto::kMaxMobHits )
		s_nSeen = count;  // first look, or Minecraft restarted: don't replay old ones
	for ( ; s_nSeen != count; ++s_nSeen )
	{
		const proto::MobHit &hit = hits.ring[ s_nSeen % proto::kMaxMobHits ];
		// Minecraft (x, y up, z south) to TF2 (x, y north, z up): the inverse of ToMinecraft.
		Vector pos( ( hit.x - 0.5f ) * UNITS_PER_BLOCK + s_vecOrigin.x,
			-( hit.z - 0.5f ) * UNITS_PER_BLOCK + s_vecOrigin.y,
			( hit.y - (float)MC_FLOOR_Y ) * UNITS_PER_BLOCK + s_vecOrigin.z );
		FortCraft_ShowMobHit( pos, (int)( hit.damage + 0.5f ), hit.killed == 1 );
		// Crits, headshots and backstabs (FC_CRIT) and mini-crits on mobs: TF2's crit sound and
		// the "CRITICAL HIT!!!" / "Mini crit!" text above the mob, as on a TF2 player.
		if ( hit.source & ( FC_CRIT | FC_MINICRIT ) )
		{
			const bool bFull = ( hit.source & FC_CRIT ) != 0;
			DispatchParticleEffect( bFull ? "crit_text" : "minicrit_text", pos + Vector( 0, 0, 16 ), vec3_angle );
			CLocalPlayerFilter filter;
			C_BaseEntity::EmitSound( filter, SOUND_FROM_LOCAL_PLAYER, bFull ? "TFPlayer.CritHit" : "TFPlayer.CritHitMini" );
		}
		// MvM Explosive Headshot hitting the mobs around the head hit: TF2's flash and sound.
		if ( hit.source & proto::kMobHitExplosiveHeadshot )
		{
			DispatchParticleEffect( "dxhr_arm_muzzleflash", pos + Vector( 0, 0, 8 ), pos + Vector( 0, 0, 8 ), vec3_angle );
			CLocalPlayerFilter filter;
			C_BaseEntity::EmitSound( filter, SOUND_FROM_WORLD, "Weapon_Upgrade.ExplosiveHeadshot", &pos );
		}
		// A gassed mob exploding (Gas Passer with "explode on ignite"), as TF2 does to players.
		if ( hit.source & proto::kMobHitGasExplosion )
		{
			DispatchParticleEffect( "dragons_fury_effect", pos - Vector( 0, 0, 32 ), vec3_angle );
			CLocalPlayerFilter filter;
			C_BaseEntity::EmitSound( filter, SOUND_FROM_WORLD, "Weapon_Grenade_Pipebomb.Explode", &pos );
		}
	}
}

// The kill icon TF2 would show for the local player's current weapon (its "item_iconname", or its
// class name without "tf_weapon_"), as in a player_death event's "weapon".
static void LocalKillIcon( C_TFPlayer *pPlayer, char *pszOut, int nOut )
{
	V_strncpy( pszOut, "skull_tf", nOut );
	C_TFWeaponBase *pWeapon = pPlayer ? pPlayer->GetActiveTFWeapon() : NULL;
	if ( !pWeapon )
		return;
	CEconItemView *pItem = pWeapon->GetAttributeContainer() ? pWeapon->GetAttributeContainer()->GetItem() : NULL;
	const char *pszIcon = pItem && pItem->GetStaticData() ? pItem->GetStaticData()->GetIconClassname() : NULL;
	if ( pszIcon && pszIcon[ 0 ] )
	{
		V_strncpy( pszOut, pszIcon, nOut );
		return;
	}
	const char *pszClass = pWeapon->GetName();
	if ( pszClass && !V_strnicmp( pszClass, "tf_weapon_", 10 ) )
		V_strncpy( pszOut, pszClass + 10, nOut );
}

// Echo where you are (Alex, 2026-10-12): Minecraft measures the space around the player (caves,
// rooms, open sky) and TF2 sets its room echo to match; TF2 fades between presets itself.
static void PollRoomSound()
{
	const proto::RoomSound &room = At< proto::RoomSound >( proto::kOffRoomSound );
	static uint32_t s_nSeq = 0;
	const uint32_t seq = *(volatile const uint32_t *)&room.seq;
	if ( seq == s_nSeq )
		return;
	s_nSeq = seq;
	const int nPreset = (int)MIN( room.preset, 28u );
	engine->ClientCmd_Unrestricted( VarArgs( "dsp_room %d\n", nPreset ) );
	Msg( "FortCraft sound: room echo preset %d\n", nPreset );
}

// Mobs the player killed: TF2's kill feed, "you - weapon icon - mob" (Alex, 2026-10-12).
static void PollMobKills()
{
	const proto::MobKills &kills = At< proto::MobKills >( proto::kOffMobKills );
	static uint32_t s_nSeen = 0xFFFFFFFF;
	uint32_t count = *(volatile const uint32_t *)&kills.count;
	if ( s_nSeen == 0xFFFFFFFF || count < s_nSeen || count - s_nSeen > proto::kMaxMobKills )
		s_nSeen = count;  // first look, or Minecraft restarted: don't replay old ones
	if ( s_nSeen == count )
		return;
	CHudBaseDeathNotice *pFeed = dynamic_cast< CHudBaseDeathNotice * >( gHUD.FindElement( "CTFHudDeathNotice" ) );
	C_TFPlayer *pPlayer = C_TFPlayer::GetLocalTFPlayer();
	for ( ; s_nSeen != count; ++s_nSeen )
	{
		const proto::MobKill &kill = kills.ring[ s_nSeen % proto::kMaxMobKills ];
		char szName[ sizeof( kill.name ) ];
		V_memcpy( szName, kill.name, sizeof( szName ) );
		szName[ sizeof( szName ) - 1 ] = 0;
		char szIcon[ 32 ];
		if ( kill.flags & proto::kMobKillSentry )
			V_strncpy( szIcon, "obj_sentrygun", sizeof( szIcon ) );
		else if ( kill.flags & proto::kMobKillBackstab )
			V_strncpy( szIcon, "backstab", sizeof( szIcon ) );
		else
			LocalKillIcon( pPlayer, szIcon, sizeof( szIcon ) );
		if ( pFeed && pPlayer )
		{
			const int iTeam = pPlayer->GetTeamNumber();
			// Robots are the other team's colour, like MvM; other mobs are neutral grey.
			const int iVictimTeam = ( kill.flags & proto::kMobKillRobot ) ? ( iTeam == TF_TEAM_BLUE ? TF_TEAM_RED : TF_TEAM_BLUE ) : TEAM_UNASSIGNED;
			pFeed->FortCraft_AddMobKill( pPlayer->GetPlayerName(), iTeam, szIcon, szName, iVictimTeam,
				( kill.flags & proto::kMobKillCrit ) != 0 );
		}
	}
}

void FortCraft_OnRenderStart()
{
	static bool s_bReadSwitches;
	if ( !s_bReadSwitches )
	{
		s_bReadSwitches = true;
		s_bNoOverlay = CommandLine()->FindParm( "-fortcraft_no_overlay" ) != 0;
		s_bForceCpu = CommandLine()->FindParm( "-fortcraft_cpu_overlay" ) != 0;
		if ( s_bNoOverlay )
			Msg( "FortCraft: overlay off (-fortcraft_no_overlay)\n" );
	}
	if ( !s_bHidden && CommandLine()->FindParm( "-fortcraft_hidden" ) )
	{
		s_bHidden = true;
		FortCraftPlat_HideOwnWindows( PlatLog );
	}
	if ( s_flRehideAt > 0.0 && Plat_FloatTime() >= s_flRehideAt )
	{
		s_flRehideAt = 0.0;
		FortCraftPlat_HideOwnWindows( PlatLog );
		// A mode change can also make TF2 the active window, which steals the keyboard and
		// locks the mouse; hand focus back to whoever had it (Minecraft).
		FortCraftPlat_RestoreFocus( PlatLog );
	}

	TryOpen();
	if ( !s_pShm )
		return;

	proto::Header &h = Hdr();
	bool bLinked = h.magic == proto::kMagic && h.version == proto::kVersion && HostAlive();
	if ( bLinked != s_bLinked )
	{
		s_bLinked = bLinked;
		if ( bLinked )
		{
			h.guestPid = (uint32_t)FortCraftPlat_ProcessId();
			Msg( "FortCraft: linked to Minecraft (pid %u)\n", h.hostPid );
		}
		else
		{
			Msg( "FortCraft: Minecraft link lost (magic %08X version %u)\n", h.magic, h.version );
			// A hidden TF2 can't be closed by hand, so it closes with Minecraft.
			if ( s_bHidden )
				engine->ClientCmd_Unrestricted( "quit" );
		}
	}
	if ( !s_bLinked )
		return;

	++s_nFrame;
	h.guestFrame = s_nFrame;
	*(volatile uint64_t *)&h.guestHeartbeatMs = FortCraftPlat_NowMs();

	HideMenus();
	HideMapEntities();
	// No TF2 screen shake (explosions, fall damage, earthquakes) or screen fades: Minecraft's
	// camera follows TF2's eye, so a shake moved Minecraft's world too. (Recoil and other view
	// punches are already off: sv_suppress_viewpunch.)
	if ( vieweffects )
	{
		ScreenShake_t stop = { SHAKE_STOP, 0.0f, 0.0f, 0.0f };
		vieweffects->Shake( stop );
		vieweffects->ClearAllFades();
	}
	MatchHostDisplay();
	AlignHiddenWindowToMinecraft();
	PublishGpuOverlay();

	// TF2's own mouse handling must never grab or centre the cursor: Minecraft owns the mouse,
	// and when Minecraft pauses it releases the cursor, which TF2 must not take. Deactivating
	// TF2's mouse alone wasn't enough (Alex: still locked), so also switch TF2's mouse input
	// off and keep its UI cursor "visible", which stops TF2's UI from locking it to the centre.
	static bool s_bMouseOff;
	if ( !s_bMouseOff )
	{
		s_bMouseOff = true;
		engine->ClientCmd_Unrestricted( "cl_mouseenable 0; m_rawinput 0\n" );
	}
	if ( ::input )
		::input->DeactivateMouse();
	if ( vgui::surface() )
		vgui::surface()->SetCursorAlwaysVisible( true );
	FreeCursorIfMinecraftLetGo();
	TurnBackpackToMinecraftItems();
	SyncBackpack();
	SyncHand();

	C_TFPlayer *pPlayer = C_TFPlayer::GetLocalTFPlayer();
	if ( !pPlayer )
		return;
	AutoJoin( pPlayer );
	ApplyLatestLook( pPlayer );
	if ( s_bHaveOrigin )
		PollMobHits();
		PollMobKills();
		PollRoomSound();
	PollUiEvents( pPlayer );
	// Raider robots keep moving while the merc is dead or respawning (Alex, 2026-10-11: they
	// froze until you were back), so they're updated before the alive check below.
	if ( s_bHaveOrigin )
		UpdateRobots();
	if ( !pPlayer->IsAlive() || pPlayer->GetTeamNumber() < FIRST_GAME_TEAM )
		return;

	// Position after this frame's interpolation and prediction: what TF2 is about to draw.
	Vector pos = pPlayer->GetAbsOrigin();
	NotePredictionErrors( pPlayer, pos );
	static int s_nLastWaterLevel = -1;
	if ( pPlayer->GetWaterLevel() != s_nLastWaterLevel )
	{
		s_nLastWaterLevel = pPlayer->GetWaterLevel();
		Msg( "FortCraft water: level=%d at %.1f %.1f %.1f\n", s_nLastWaterLevel, pos.x, pos.y, pos.z );
	}
	const char *pszMap = engine->GetLevelName();
	if ( !s_bHaveOrigin || V_strcmp( pszMap, s_szOriginMap ) != 0 )
	{
		s_bHaveOrigin = true;
		s_vecOrigin = pos;
		V_strncpy( s_szOriginMap, pszMap, sizeof( s_szOriginMap ) );
		Msg( "FortCraft: origin set at %.1f %.1f %.1f on %s\n", pos.x, pos.y, pos.z, pszMap );
	}

	// Re-centring (see Recentre in the protocol): when TF2's server has moved everything back by
	// a whole number of blocks, move the anchor by the same amount the moment our player jumps,
	// so Minecraft sees no move. If the jump isn't seen within 2 s (e.g. dead), apply it anyway.
	{
		proto::Recentre &r = At< proto::Recentre >( proto::kOffRecentre );
		static Vector s_vecLastPos = pos;
		static double s_flPendingSince;
		const double dx = r.serverX - r.clientX, dy = r.serverY - r.clientY;
		if ( dx != 0.0 || dy != 0.0 )
		{
			if ( s_flPendingSince == 0.0 )
				s_flPendingSince = Plat_FloatTime();
			const Vector jump = pos - s_vecLastPos;
			const bool bJumped = fabs( jump.x + dx ) < 200.0 && fabs( jump.y + dy ) < 200.0;
			// A respawn far away (a bed elsewhere) re-centres and teleports at once: any big jump.
			const bool bRespawned = jump.Length2D() > 2000.0f;
			if ( bJumped || bRespawned || Plat_FloatTime() - s_flPendingSince > 2.0 )
			{
				s_vecOrigin.x -= (float)dx;
				s_vecOrigin.y -= (float)dy;
				r.clientX = r.serverX;
				r.clientY = r.serverY;
				s_flPendingSince = 0.0;
				Msg( "FortCraft: re-centre applied (%.0f %.0f units, %s)\n", dx, dy, bJumped ? "player jumped" : bRespawned ? "respawned" : "timeout" );
			}
		}
		s_vecLastPos = pos;
	}

	// Tell TF2's movement code (client and server) where Minecraft's blocks sit in TF2's world.
	// Rewritten every frame, since Minecraft clears it when it restarts.
	proto::Anchor &anchor = At< proto::Anchor >( proto::kOffAnchor );
	anchor.x = s_vecOrigin.x;
	anchor.y = s_vecOrigin.y;
	anchor.z = s_vecOrigin.z;
	anchor.valid = 1;

	// TF2 is Z-up with +Y north; Minecraft is Y-up with -Z north.
	double x = ( pos.x - s_vecOrigin.x ) / UNITS_PER_BLOCK + 0.5;
	double y = ( pos.z - s_vecOrigin.z ) / UNITS_PER_BLOCK + MC_FLOOR_Y;
	double z = -( pos.y - s_vecOrigin.y ) / UNITS_PER_BLOCK + 0.5;
	QAngle eye = pPlayer->EyeAngles();
	float yaw = AngleNormalize( -eye.y - 90.0f );
	float pitch = eye.x;

	proto::PlayerState &ps = At< proto::PlayerState >( proto::kOffPlayerState );
	uint32_t seq = ps.seq;
	*(volatile uint32_t *)&ps.seq = ( seq + 1 ) | 1;  // odd: writing
	ps.flags = proto::kPlayerValid;
	ps.sample = ++s_nSample;
	ps.x = x;
	ps.y = y;
	ps.z = z;
	ps.yaw = yaw;
	ps.pitch = pitch;
	ps.eyeHeight = pPlayer->GetViewOffset().z / UNITS_PER_BLOCK;
	*(volatile uint32_t *)&ps.seq = ( ( seq + 1 ) | 1 ) + 1;  // even: done

	WriteProjectiles();
	UpdateFarOcclusion( pPlayer );

	LinkLog( "sample=%llu tick=%d alpha=%.3f sent=(%.4f,%.4f,%.4f) yaw=%.2f tf2=(%.1f,%.1f,%.1f) speed=%.0f onGround=%d\n",
		(unsigned long long)s_nSample, gpGlobals->tickcount, gpGlobals->interpolation_amount, x, y, z, yaw,
		pos.x, pos.y, pos.z, pPlayer->GetAbsVelocity().Length2D(), ( pPlayer->GetFlags() & FL_ONGROUND ) ? 1 : 0 );
}
