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
	pCmd->buttons = buttons;

	// Minecraft's hotbar slot picks the TF2 weapon slot (1: primary, 2: secondary, 3: melee...).
	// Retry a missed request, but stop after it succeeds so TF2 can keep its builder equipped.
	static int s_nLastSlot = -1;
	static int s_nLastClass = -1;
	static int s_nLastDesiredWeapon = -1;
	static bool s_bSwitchPending = false;
	if ( (int)in.weaponSlot != s_nLastSlot )
	{
		s_nLastSlot = (int)in.weaponSlot;
		s_bSwitchPending = true;
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
		const int nDesiredWeapon = pWeapon ? pWeapon->entindex() : -1;

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
			pCmd->weaponselect = pWeapon->entindex();
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
		| ( engine->Con_IsVisible() ? proto::kCameraConsole : 0 );
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

// TF2's MvM upgrade screen (opened by Minecraft's enchanting table).
static CHudUpgradePanel *UpgradePanel()
{
	CHudUpgradePanel *pPanel = GET_HUDELEMENT( CHudUpgradePanel );
	return pPanel && pPanel->IsVisible() ? pPanel : NULL;
}

// A TF2 menu that takes the mouse is open.
static bool UiOpen()
{
	return ( enginevgui && enginevgui->IsGameUIVisible() ) || ( s_bUserMenu && ViewportMenuOpen() ) || UpgradePanel();
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
static void DrawBlockDepth()
{
	int counts[ 3 ] = { 0, 0, 0 };
	const FortCraftBox *lists[ 3 ] = {
		FortCraft_Boxes( &counts[ 0 ] ), FortCraft_VisualBoxes( &counts[ 1 ] ), FortCraft_MobBoxes( &counts[ 2 ] )
	};
	if ( !counts[ 0 ] && !counts[ 2 ] )
		return;
	static CMaterialReference s_WriteZ;
	if ( !s_WriteZ.IsValid() )
		s_WriteZ.Init( "engine/writez", TEXTURE_GROUP_OTHER );

	CMatRenderContextPtr pRenderContext( materials );
	pRenderContext->Bind( s_WriteZ );
	const int kBoxesPerBatch = 500;  // 6 quads = 24 vertices, 36 indices each; a dynamic mesh takes at most 32768 indices
	for ( int list = 0; list < 3; ++list )
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
	static int s_nCapH = CommandLine()->ParmValue( "-fortcraft_overlay_height", 1080 );
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

// First free slot on the backpack's last page: no Steam item and no other Minecraft stack there.
static int FreeLastPagePosition( CPlayerInventory *pInv )
{
	const int nPerPage = 50;  // BACKPACK_SLOTS_PER_PAGE
	const int nMax = pInv->GetMaxItemCount();
	const int nFirst = ( ( nMax + nPerPage - 1 ) / nPerPage - 1 ) * nPerPage + 1;
	for ( int pos = nFirst; pos < nFirst + nPerPage && pos <= nMax; ++pos )
	{
		bool bUsed = false;
		for ( int i = 0; i < pInv->GetItemCount() && !bUsed; ++i )
			bUsed = ExtractBackpackPositionFromBackend( pInv->GetItem( i )->GetInventoryPosition() ) == (uint32)pos;
		for ( int i = 0; i < s_LocalStacks.Count() && !bUsed; ++i )
			bUsed = s_LocalStacks[ i ]->pos == pos;
		if ( !bUsed )
			return pos;
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
		Msg( "FortCraft backpack: no free slot on the last page for %s\n", st.id );
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
	PollUiEvents( pPlayer );
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
