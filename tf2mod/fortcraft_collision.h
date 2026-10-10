// FortCraft: Minecraft blocks as solid geometry for TF2 player movement.
//
// Minecraft writes the solid blocks around the player into shared memory as boxes (see
// protocol/fortcraft_protocol.h, BlockBoxes). This code is compiled into both TF2 modules
// (client.dll for prediction, server.dll for the real movement) so both agree, and is called
// from CTFGameMovement's player traces.
#pragma once

#include "gametrace.h"

// True while Minecraft is linked and the anchor is set. Then only Minecraft's blocks count:
// the TF2 map's own walls and floors are ignored for player movement.
bool FortCraft_Active();

// Creative always has unlimited ammunition. Survival follows the optional TF2 override.
bool FortCraft_HostCreative();

// The one non-solid, networked TF2 presentation entity for Minecraft's selected friendly
// mob. The server creates/moves it; the client resolves its networked entity index.
class CBaseEntity;
CBaseEntity *FortCraft_MedicProxy();
bool FortCraft_IsMedicProxy( const CBaseEntity *pEntity );
bool FortCraft_MedicTargetInfo( char *name, int nameBytes, float *health, float *maxHealth );

// A trace that hit nothing, from start to end. Used in place of the engine's map trace.
void FortCraft_ClearTrace( const Vector &start, const Vector &end, trace_t &pm );

// Minecraft's block boxes near the player, in TF2 units (empty when not linked).
struct FortCraftBox
{
	Vector mins, maxs;
};
const FortCraftBox *FortCraft_Boxes( int *pCount );

// Depth-only proxies for visible walk-through blocks and Minecraft mobs. Never used for
// movement collision; TF2 draws these before projectiles so they can be hidden by Minecraft.
const FortCraftBox *FortCraft_VisualBoxes( int *pCount );
const FortCraftBox *FortCraft_MobBoxes( int *pCount );

// True if the point is inside one of Minecraft's blocks (TF2 units).
bool FortCraft_PointInBlocks( const Vector &pos );
bool FortCraft_PointInWater( const Vector &pos );

// Clip a player hull trace against Minecraft's block boxes. If a box is hit before what the
// engine trace already found, overwrites pm with that hit (as world geometry).
void FortCraft_ClipHullTrace( const Vector &start, const Vector &end, const Vector &hullMins, const Vector &hullMaxs, trace_t &pm );

// True if a Minecraft mob box is crossed before a terrain box on this short projectile path.
// Used for arrows: their normal TF2 touch sees both kinds of Minecraft box as world.
bool FortCraft_MobBeforeBlocks( const Vector &start, const Vector &end, const Vector &hullMins, const Vector &hullMaxs );

// The centre of the nearest hostile Minecraft mob (zombie, slime...) within range that no block
// hides from `from` (TF2 units). Used by Engineer sentries.
bool FortCraft_NearestHostileMob( const Vector &from, float flRange, Vector &target );

// Minecraft's friction for the block under the player, as TF2 surface friction (1 normal, lower on
// ice); -1 when not linked or nothing special. Used by CGameMovement::CategorizeGroundSurface.
float FortCraft_GroundFriction();

// Bits 8-15: bleed seconds (Boston Basher, Tribalman's Shiv, ...), for Minecraft to apply to mobs.
// Both modules: the client shows crit text for mob hits flagged FC_CRIT / FC_MINICRIT.
enum FortCraftShotFlags { FC_MELEE = 1, FC_CRIT = 2, FC_MINICRIT = 4, FC_HEADSHOT = 8, FC_KNIFE = 16, FC_SENTRY = 32,
	FC_BLEED_SHIFT = 8 };

#ifndef CLIENT_DLL

enum FortCraftTool { FC_GENERIC = 0, FC_SHOVEL = 1, FC_PICKAXE = 2, FC_AXE = 3, FC_BLADE = 4 };
class CTFWeaponBase;
// TF2 server: report a bullet / melee swing to Minecraft (TF2 units and damage).
void FortCraft_ReportShot( const Vector &src, const Vector &dir, float flDistance, float flDamage,
	unsigned int flags = 0, unsigned int weapon = 0, unsigned int tool = FC_GENERIC, float headshotRange = 0 );
void FortCraft_ReportWeaponShot( CTFWeaponBase *weapon, const Vector &src, const Vector &dir,
	float distance, float damage, int damageType, bool melee, bool mini = false );
void FortCraft_ReportBlast( const Vector &src, float radius, float damage, int damageType );

// TF2 server: fire touched the world or a mob here (TF2 units). Minecraft sets mobs within the
// radius on fire and lights a fire block; bFlame thins out the flamethrower's many flames. A
// negative radius puts fire out instead (fire blocks and burning mobs within -radius).
void FortCraft_ReportFire( const Vector &pos, float flRadius, bool bFlame );

// TF2 server, once per player think: the player's buildings to Minecraft, and hostile mobs'
// hits on them back (TF2 damage = Minecraft health points x building max health / 20).
class CBaseEntity;
void FortCraft_WriteBuildings( CBaseEntity **ppObjects, int nCount );

// TF2 server, once per player think: when the player is far from the middle of TF2's playing
// field, move the player, buildings and projectiles back by whole blocks (Minecraft sees no move).
void FortCraft_MaybeRecentre( CBaseEntity *pPlayer, CBaseEntity **ppObjects, int nObjects );
void FortCraft_ApplyBuildingHits();

// TF2 server, once per player think: apply damage Minecraft did to its player (mob hits etc.).
class CBaseEntity;
void FortCraft_ApplyMinecraftDamage( CBaseEntity *pPlayer );

// TF2 server, end of player spawn: move the player onto Minecraft's ground (Minecraft's spawn point).
void FortCraft_OnPlayerSpawn( CBaseEntity *pPlayer );

// For two seconds after spawn, lift a player out if the refreshed Minecraft boxes reveal
// that the full TF2 hull overlaps a block (the block scan may arrive after the teleport).
void FortCraft_EnsureClearSpawn( CBaseEntity *pPlayer );
#endif
