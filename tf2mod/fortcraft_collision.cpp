// FortCraft: Minecraft blocks as solid geometry for TF2 player movement. See
// fortcraft_collision.h and protocol/fortcraft_protocol.h.
#include "cbase.h"
#include "fortcraft_collision.h"
#include "utlvector.h"
#include "coordsize.h"  // DIST_EPSILON
#include "takedamageinfo.h"
#include "tf_shareddefs.h"
#include "../protocol/fortcraft_protocol.h"
#include "fortcraft_platform.h"

#ifdef CLIENT_DLL
#include "c_world.h"
#else
#include "world.h"
#include "tf_weaponbase.h"
#include "tf_weapon_sniperrifle.h"
#include "tf_player.h"
#include "tf_obj.h"
#include "tf_gamerules.h"
#include "player_vs_environment/tf_upgrades.h"
#include "tf_upgrades_shared.h"
#include "entity_currencypack.h"
#endif

// memdbgon must be the last include file in a .cpp file!!!
#include "tier0/memdbgon.h"


namespace proto = fortcraft::proto;

static unsigned char *s_pShm;
static double s_flLastOpenTry;
static bool s_bActive;
static uint32_t s_nSeenSeq = 0xFFFFFFFF;
static Vector s_vecSeenAnchor;
static CUtlVector< FortCraftBox > s_Boxes;
static CUtlVector< FortCraftBox > s_WaterBoxes;
static uint32_t s_nSeenWaterSeq = 0xFFFFFFFF;
static Vector s_vecSeenWaterAnchor;
static CUtlVector< FortCraftBox > s_VisualBoxes;
static CUtlVector< FortCraftBox > s_Mobs;  // Minecraft mobs' hitboxes, TF2 units
static CUtlVector< unsigned char > s_MobHostile;  // same order: proto::kMobHostile / kMobSentrySees
static uint32_t s_nSeenMobSeq = 0xFFFFFFFF;
static Vector s_vecSeenMobAnchor;
static uint32_t s_nSeenVisualSeq = 0xFFFFFFFF;
static Vector s_vecSeenVisualAnchor;

static void TryOpen()
{
	if ( s_pShm || Plat_FloatTime() - s_flLastOpenTry < 1.0 )
		return;
	s_flLastOpenTry = Plat_FloatTime();
	s_pShm = (unsigned char *)FortCraftPlat_OpenLink( (size_t)proto::kMappingBytes );
}

// Minecraft (x, y up, z south) box to TF2 (x, y north, z up), around the anchor.
static void ToTF2( const proto::BlockBox &b, const Vector &anchor, FortCraftBox &o )
{
	const float s = (float)proto::kUnitsPerBlock;
	o.mins.Init( ( b.minX - 0.5f ) * s + anchor.x, -( b.maxZ - 0.5f ) * s + anchor.y, ( b.minY + 60.0f ) * s + anchor.z );
	o.maxs.Init( ( b.maxX - 0.5f ) * s + anchor.x, -( b.minZ - 0.5f ) * s + anchor.y, ( b.maxY + 60.0f ) * s + anchor.z );
}

// Mob hitboxes change every frame; re-read when Minecraft has written new ones.
static void ReadMobs( const Vector &anchor )
{
	const proto::MobBoxes &src = *(const proto::MobBoxes *)( s_pShm + proto::kOffMobBoxes );
	uint32_t seq = *(volatile const uint32_t *)&src.seq;
	if ( ( seq & 1 ) || ( seq == s_nSeenMobSeq && anchor == s_vecSeenMobAnchor ) )
		return;
	CUtlVector< FortCraftBox > mobs;
	CUtlVector< unsigned char > hostile;
	const unsigned char *pHostile = s_pShm + proto::kOffMobHostile;
	uint32_t count = MIN( src.count, proto::kMaxMobBoxes );
	for ( uint32_t i = 0; i < count; ++i )
	{
		ToTF2( src.boxes[ i ], anchor, mobs[ mobs.AddToTail() ] );
		hostile.AddToTail( pHostile[ i ] );
	}
	if ( *(volatile const uint32_t *)&src.seq != seq )
		return;
	s_Mobs.Swap( mobs );
	s_MobHostile.Swap( hostile );
	s_nSeenMobSeq = seq;
	s_vecSeenMobAnchor = anchor;
}

// Walk-through grass, flowers and other visible blocks have no collision boxes, but must
// still hide projectiles behind them. Their outline boxes are only an approximate depth mask.
static void ReadVisuals( const Vector &anchor )
{
	const proto::VisualBoxes &src = *(const proto::VisualBoxes *)( s_pShm + proto::kOffVisualBoxes );
	uint32_t seq = *(volatile const uint32_t *)&src.seq;
	if ( ( seq & 1 ) || ( seq == s_nSeenVisualSeq && anchor == s_vecSeenVisualAnchor ) )
		return;
	CUtlVector< FortCraftBox > boxes;
	uint32_t count = MIN( src.count, proto::kMaxVisualBoxes );
	boxes.EnsureCapacity( count );
	for ( uint32_t i = 0; i < count; ++i )
		ToTF2( src.boxes[ i ], anchor, boxes[ boxes.AddToTail() ] );
	if ( *(volatile const uint32_t *)&src.seq != seq )
		return;
	s_VisualBoxes.Swap( boxes );
	s_nSeenVisualSeq = seq;
	s_vecSeenVisualAnchor = anchor;
}

static void ReadWater( const Vector &anchor )
{
	const proto::WaterBoxes &src = *(const proto::WaterBoxes *)( s_pShm + proto::kOffWaterBoxes );
	uint32_t seq = *(volatile const uint32_t *)&src.seq;
	if ( ( seq & 1 ) || ( seq == s_nSeenWaterSeq && anchor == s_vecSeenWaterAnchor ) )
		return;
	CUtlVector< FortCraftBox > boxes;
	uint32_t count = MIN( src.count, proto::kMaxWaterBoxes );
	boxes.EnsureCapacity( count );
	for ( uint32_t i = 0; i < count; ++i )
		ToTF2( src.boxes[ i ], anchor, boxes[ boxes.AddToTail() ] );
	if ( *(volatile const uint32_t *)&src.seq != seq )
		return;
	s_WaterBoxes.Swap( boxes );
	s_nSeenWaterSeq = seq;
	s_vecSeenWaterAnchor = anchor;
}

// Re-read the box list when Minecraft has written a new one (or the anchor moved).
void FortCraft_InstallTraceWrapper();

static void Refresh()
{
	static bool s_bWrapped;
	if ( !s_bWrapped )
	{
		s_bWrapped = true;
		FortCraft_InstallTraceWrapper();
	}
	s_bActive = false;
	TryOpen();
	if ( !s_pShm )
		return;
	const proto::Header &h = *(const proto::Header *)( s_pShm + proto::kOffHeader );
	if ( h.magic != proto::kMagic || h.version != proto::kVersion )
	{
		s_Boxes.RemoveAll();
		s_WaterBoxes.RemoveAll();
		s_VisualBoxes.RemoveAll();
		s_Mobs.RemoveAll();
		return;
	}
	const proto::Anchor &a = *(const proto::Anchor *)( s_pShm + proto::kOffAnchor );
	if ( !a.valid )
	{
		s_Boxes.RemoveAll();
		s_WaterBoxes.RemoveAll();
		s_VisualBoxes.RemoveAll();
		s_Mobs.RemoveAll();
		return;
	}
	s_bActive = true;
	Vector anchor( (float)a.x, (float)a.y, (float)a.z );

	ReadMobs( anchor );
	ReadVisuals( anchor );
	ReadWater( anchor );

	const proto::BlockBoxes &src = *(const proto::BlockBoxes *)( s_pShm + proto::kOffBlockBoxes );
	uint32_t seq = *(volatile const uint32_t *)&src.seq;
	if ( ( seq & 1 ) || ( seq == s_nSeenSeq && anchor == s_vecSeenAnchor ) )
		return;  // mid-write (keep the old list) or nothing new

	const float s = (float)proto::kUnitsPerBlock;
	CUtlVector< FortCraftBox > boxes;
	uint32_t count = MIN( src.count, proto::kMaxBlockBoxes );
	boxes.EnsureCapacity( count );
	for ( uint32_t i = 0; i < count; ++i )
	{
		const proto::BlockBox &b = src.boxes[ i ];
		// Minecraft (x, y up, z south) to TF2 (x, y north, z up), around the anchor.
		FortCraftBox &o = boxes[ boxes.AddToTail() ];
		o.mins.Init( ( b.minX - 0.5f ) * s + anchor.x, -( b.maxZ - 0.5f ) * s + anchor.y, ( b.minY + 60.0f ) * s + anchor.z );
		o.maxs.Init( ( b.maxX - 0.5f ) * s + anchor.x, -( b.minZ - 0.5f ) * s + anchor.y, ( b.maxY + 60.0f ) * s + anchor.z );
	}
	if ( *(volatile const uint32_t *)&src.seq != seq )
		return;  // Minecraft wrote while we copied; try again next trace

	s_Boxes.Swap( boxes );
	s_nSeenSeq = seq;
	s_vecSeenAnchor = anchor;
}

static CBaseEntity *World()
{
#ifdef CLIENT_DLL
	return GetClientWorldEntity();
#else
	return GetWorldEntity();
#endif
}

static void SetHit( trace_t &pm, const Vector &start, const Vector &delta, float fraction, int axis, float sign )
{
	pm.fraction = fraction;
	pm.endpos = start + delta * fraction;
	pm.plane.normal.Init();
	pm.plane.normal[ axis ] = sign;
	pm.plane.dist = DotProduct( pm.plane.normal, pm.endpos );
	pm.plane.type = (byte)axis;
	pm.plane.signbits = sign < 0 ? (byte)( 1 << axis ) : 0;
	pm.contents = CONTENTS_SOLID;
	pm.m_pEnt = World();
	pm.hitbox = 0;
	pm.hitgroup = 0;
	pm.physicsbone = 0;
	pm.surface.name = "fortcraft_block";
	pm.surface.surfaceProps = 0;
	pm.surface.flags = 0;
}

const FortCraftBox *FortCraft_Boxes( int *pCount )
{
	Refresh();
	*pCount = s_bActive ? s_Boxes.Count() : 0;
	return s_Boxes.Base();
}

const FortCraftBox *FortCraft_VisualBoxes( int *pCount )
{
	Refresh();
	*pCount = s_bActive ? s_VisualBoxes.Count() : 0;
	return s_VisualBoxes.Base();
}

const FortCraftBox *FortCraft_MobBoxes( int *pCount )
{
	Refresh();
	*pCount = s_bActive ? s_Mobs.Count() : 0;
	return s_Mobs.Base();
}

bool FortCraft_Active()
{
	Refresh();
	return s_bActive;
}

float FortCraft_GroundFriction()
{
	Refresh();
	if ( !s_bActive || !s_pShm )
		return -1.0f;
	const float mc = ( (const proto::Ground *)( s_pShm + proto::kOffGround ) )->friction;
	if ( mc <= 0.0f || fabsf( mc - 0.6f ) < 0.01f )
		return -1.0f;  // unknown, or an ordinary block
	// Minecraft keeps (friction x 0.91) of its speed each tick: 0.6 normal, 0.98 ice. Map the part
	// it loses onto TF2's surface friction (1 = normal), keeping some control on ice as Minecraft does.
	return clamp( ( 1.0f - mc ) / 0.4f, 0.15f, 1.0f );
}

bool FortCraft_MobBackstabReady()
{
	Refresh();
	if ( !s_bActive || !s_pShm )
		return false;
	return ( (const volatile proto::MobBackstab *)( s_pShm + proto::kOffMobBackstab ) )->ready == 1;
}

bool FortCraft_HostCreative()
{
	Refresh();
	if ( !s_bActive || !s_pShm )
		return false;
	const proto::HostDisplay &display = *(const proto::HostDisplay *)( s_pShm + proto::kOffHostDisplay );
	return ( display.flags & proto::kHostCreative ) != 0;
}

static bool ReadMedicTarget( proto::MedicTarget &out )
{
	Refresh();
	if ( !s_bActive || !s_pShm )
		return false;
	const proto::Header &header = *(const proto::Header *)( s_pShm + proto::kOffHeader );
	if ( !header.hostHeartbeatMs || FortCraftPlat_NowMs() - header.hostHeartbeatMs > proto::kHeartbeatTimeoutMs )
		return false;
	const proto::MedicTarget &source = *(const proto::MedicTarget *)( s_pShm + proto::kOffMedicTarget );
	const uint32_t seq = *(volatile const uint32_t *)&source.seq;
	if ( seq & 1 )
		return false;
	V_memcpy( &out, &source, sizeof( out ) );
	return seq == *(volatile const uint32_t *)&source.seq && out.entityId > 0;
}

CBaseEntity *FortCraft_MedicProxy()
{
	proto::MedicTarget target;
	const bool valid = ReadMedicTarget( target );
#ifdef CLIENT_DLL
	if ( !valid || target.proxyEntIndex <= 0 )
		return NULL;
	CBaseEntity *clientProxy = cl_entitylist->GetEnt( target.proxyEntIndex );
	if ( !clientProxy )
	{
		static float s_flNextMissingProxyLog;
		if ( gpGlobals->curtime >= s_flNextMissingProxyLog )
		{
			s_flNextMissingProxyLog = gpGlobals->curtime + 1.0f;
			Msg( "FortCraft Medic client: server proxy index %d not in client entity list\n", target.proxyEntIndex );
		}
	}
	return clientProxy;
#else
	static EHANDLE proxy;
	static int previousId;
	proto::MedicTarget *shared = s_pShm ? (proto::MedicTarget *)( s_pShm + proto::kOffMedicTarget ) : NULL;
	if ( !valid )
	{
		if ( proxy )
		{
			Msg( "FortCraft: Medic proxy removed (target lost)\n" );
			UTIL_Remove( proxy );
			proxy = NULL;
		}
		previousId = 0;
		if ( shared ) shared->proxyEntIndex = 0;
		return NULL;
	}
	if ( !proxy )
	{
		CBaseEntity *entity = CreateEntityByName( "prop_dynamic" );
		if ( !entity )
			return NULL;
		entity->KeyValue( "model", "models/weapons/c_models/c_medigun/c_medigun.mdl" );
		entity->KeyValue( "solid", "0" );
		DispatchSpawn( entity );
		entity->SetMoveType( MOVETYPE_NONE );
		entity->SetSolid( SOLID_NONE );
		entity->AddEffects( EF_NODRAW );
		// Dynamic props are not living entities by default. The Medigun's
		// FindAndHealTargets rejects anything for which IsAlive() is false.
		entity->m_lifeState = LIFE_ALIVE;
		// The proxy is invisible/non-solid, but its network handle and origin must
		// still reach TF2's client for the native beam and target-ID panel.
		entity->RemoveFlag( FL_STATICPROP );
		entity->SetTransmitState( FL_EDICT_ALWAYS );
		entity->ChangeTeam( TF_TEAM_RED );
		proxy = entity;
		Msg( "FortCraft: Medic proxy spawned TF2 entity %d\n", entity->entindex() );
	}
	if ( previousId != target.entityId )
	{
		Msg( "FortCraft: Medic proxy targeting Minecraft entity %d (%s)\n", target.entityId, target.name );
		previousId = target.entityId;
	}
	const proto::Anchor &anchor = *(const proto::Anchor *)( s_pShm + proto::kOffAnchor );
	const proto::Recentre &recentre = *(const proto::Recentre *)( s_pShm + proto::kOffRecentre );
	Vector position( ( target.x - 0.5f ) * (float)proto::kUnitsPerBlock + (float)( anchor.x - recentre.serverX + recentre.clientX ),
		-( target.z - 0.5f ) * (float)proto::kUnitsPerBlock + (float)( anchor.y - recentre.serverY + recentre.clientY ),
		( target.y + 60.0f ) * (float)proto::kUnitsPerBlock + (float)anchor.z );
	proxy->SetAbsOrigin( position );
	proxy->SetMaxHealth( MAX( 1, (int)target.maxHealth ) );
	proxy->SetHealth( MAX( 1, (int)target.health ) );
	shared->proxyEntIndex = proxy->entindex();
	return proxy;
#endif
}

bool FortCraft_IsMedicProxy( const CBaseEntity *entity )
{
	return entity && entity == FortCraft_MedicProxy();
}

bool FortCraft_MedicTargetInfo( char *name, int nameBytes, float *health, float *maxHealth )
{
	proto::MedicTarget target;
	if ( !ReadMedicTarget( target ) || target.proxyEntIndex <= 0 )
		return false;
	if ( name && nameBytes > 0 )
	{
		target.name[ sizeof( target.name ) - 1 ] = 0;
		V_strncpy( name, target.name, nameBytes );
	}
	if ( health ) *health = target.health;
	if ( maxHealth ) *maxHealth = target.maxHealth;
	return true;
}

void FortCraft_ClearTrace( const Vector &start, const Vector &end, trace_t &pm )
{
	V_memset( &pm, 0, sizeof( pm ) );
	pm.surface.name = "**empty**";
	pm.startpos = start;
	pm.endpos = end;
	pm.fraction = 1.0f;
	pm.m_pEnt = NULL;
}

// Clip against one list of boxes. Mobs (bIsMobs) never trap whatever starts inside them: a
// zombie standing in you must not glue you in place.
static bool ClipAgainst( const CUtlVector< FortCraftBox > &list, bool bIsMobs, const Vector &start, const Vector &end, const Vector &hullMins, const Vector &hullMaxs, trace_t &pm )
{
	const Vector delta = end - start;
	const float length = delta.Length();

	for ( int i = 0; i < list.Count(); ++i )
	{
		// Grow the box by the hull, then trace a point (the hull's origin) against it.
		const Vector emin = list[ i ].mins - hullMaxs;
		const Vector emax = list[ i ].maxs - hullMins;

		bool bStartInside = true;
		float tEnter = -FLT_MAX, tExit = FLT_MAX;
		int enterAxis = -1;
		float enterSign = 0.0f;
		bool bMiss = false;
		for ( int ax = 0; ax < 3 && !bMiss; ++ax )
		{
			if ( start[ ax ] <= emin[ ax ] || start[ ax ] >= emax[ ax ] )
				bStartInside = false;
			if ( fabsf( delta[ ax ] ) < 1e-6f )
			{
				if ( start[ ax ] <= emin[ ax ] || start[ ax ] >= emax[ ax ] )
					bMiss = true;  // moving parallel to this slab, outside it (touching doesn't count)
				continue;
			}
			float t1 = ( emin[ ax ] - start[ ax ] ) / delta[ ax ];
			float t2 = ( emax[ ax ] - start[ ax ] ) / delta[ ax ];
			float tNear = delta[ ax ] > 0 ? t1 : t2;
			float tFar = delta[ ax ] > 0 ? t2 : t1;
			if ( tNear > tEnter )
			{
				tEnter = tNear;
				enterAxis = ax;
				enterSign = delta[ ax ] > 0 ? -1.0f : 1.0f;
			}
			tExit = MIN( tExit, tFar );
		}

		if ( bStartInside )
		{
			if ( bIsMobs )
				continue;
			// Already overlapping a block: stuck, like inside a wall.
			bool bEndInside = end.x > emin.x && end.x < emax.x && end.y > emin.y && end.y < emax.y && end.z > emin.z && end.z < emax.z;
			pm.startsolid = true;
			pm.allsolid = bEndInside;
			SetHit( pm, start, delta, 0.0f, 2, 1.0f );
			if ( bEndInside )
				return true;
			continue;
		}
		if ( bMiss || enterAxis < 0 || tEnter > tExit || tEnter < 0.0f || tEnter > 1.0f || length <= 0.0f )
			continue;

		// Stop DIST_EPSILON short of the surface measured straight out from it, as Valve's brush
		// collision does ((d1 - DIST_EPSILON) / (d1 - d2) in CM_ClipBoxToBrush). Backing off along
		// the movement instead (the old formula) left a player walking into a wall at a shallow
		// angle only DIST_EPSILON * sin(angle) away: within float error of the face, so the next
		// move could start "inside" the block, stick, and get pushed back out (wall jitter).
		float fraction = MAX( 0.0f, tEnter - DIST_EPSILON / fabsf( delta[ enterAxis ] ) );
		if ( fraction < pm.fraction )
			SetHit( pm, start, delta, fraction, enterAxis, enterSign );
	}
	return false;
}

void FortCraft_ClipHullTrace( const Vector &start, const Vector &end, const Vector &hullMins, const Vector &hullMaxs, trace_t &pm )
{
	Refresh();
	if ( !s_bActive || pm.allsolid )
		return;

	// The edge of TF2's world counts as solid. TF2's map walls are skipped (see fortcraft_trace.cpp),
	// so a rocket fired at the sky or past the loaded blocks used to fly on for ever (Alex saw them
	// as specks in the distance). Now it explodes when it reaches the edge.
	const float kWorldEdge = 15800.0f;  // just inside fortcraft_flat's walls (+-16256)
	const Vector delta = end - start;
	for ( int ax = 0; ax < 3; ++ax )
	{
		if ( fabsf( start[ ax ] ) >= kWorldEdge || fabsf( end[ ax ] ) <= kWorldEdge || fabsf( delta[ ax ] ) < 1e-6f )
			continue;
		float sign = end[ ax ] > 0 ? 1.0f : -1.0f;
		float fraction = ( sign * kWorldEdge - start[ ax ] ) / delta[ ax ];
		if ( fraction >= 0.0f && fraction < pm.fraction )
			SetHit( pm, start, delta, fraction, ax, -sign );
	}

	if ( ClipAgainst( s_Boxes, false, start, end, hullMins, hullMaxs, pm ) )
		return;
	ClipAgainst( s_Mobs, true, start, end, hullMins, hullMaxs, pm );
}

bool FortCraft_MobBeforeBlocks( const Vector &start, const Vector &end, const Vector &hullMins, const Vector &hullMaxs )
{
	Refresh();
	if ( !s_bActive )
		return false;

	trace_t mobTrace, blockTrace;
	FortCraft_ClearTrace( start, end, mobTrace );
	FortCraft_ClearTrace( start, end, blockTrace );
	ClipAgainst( s_Mobs, true, start, end, hullMins, hullMaxs, mobTrace );
	if ( !mobTrace.DidHit() )
		return false;
	ClipAgainst( s_Boxes, false, start, end, hullMins, hullMaxs, blockTrace );
	return !blockTrace.DidHit() || mobTrace.fraction < blockTrace.fraction;
}

bool FortCraft_NearestHostileMob( const Vector &from, float flRange, Vector &target )
{
	Refresh();
	if ( !s_bActive )
		return false;
	float flBest = flRange;
	bool bFound = false;
	for ( int i = 0; i < s_Mobs.Count() && i < s_MobHostile.Count(); ++i )
	{
		// Hostile, and seen by a sentry through Minecraft's whole world (not just the blocks TF2
		// knows near the player).
		if ( ( s_MobHostile[ i ] & ( proto::kMobHostile | proto::kMobSentrySees ) ) != ( proto::kMobHostile | proto::kMobSentrySees ) )
			continue;
		const Vector centre = ( s_Mobs[ i ].mins + s_Mobs[ i ].maxs ) * 0.5f;
		const float flDist = ( centre - from ).Length();
		if ( flDist >= flBest )
			continue;
		trace_t blockTrace;
		FortCraft_ClearTrace( from, centre, blockTrace );
		ClipAgainst( s_Boxes, false, from, centre, vec3_origin, vec3_origin, blockTrace );
		if ( blockTrace.DidHit() )
			continue;  // a block is in the way
		flBest = flDist;
		target = centre;
		bFound = true;
	}
	return bFound;
}

bool FortCraft_PointInBlocks( const Vector &pos )
{
	Refresh();
	if ( !s_bActive )
		return false;
	for ( int i = 0; i < s_Boxes.Count(); ++i )
	{
		const FortCraftBox &b = s_Boxes[ i ];
		if ( pos.x > b.mins.x && pos.x < b.maxs.x && pos.y > b.mins.y && pos.y < b.maxs.y && pos.z > b.mins.z && pos.z < b.maxs.z )
			return true;
	}
	return false;
}

bool FortCraft_PointInWater( const Vector &pos )
{
	Refresh();
	if ( !s_bActive )
		return false;
	for ( int i = 0; i < s_WaterBoxes.Count(); ++i )
	{
		const FortCraftBox &b = s_WaterBoxes[ i ];
		if ( pos.x > b.mins.x && pos.x < b.maxs.x && pos.y > b.mins.y && pos.y < b.maxs.y && pos.z > b.mins.z && pos.z < b.maxs.z )
			return true;
	}
	return false;
}

// ---- Shots and damage (server side) ------------------------------------------------------------
// Compiled into both TF2 modules like the rest of this file, but only TF2's server calls these.

#ifndef CLIENT_DLL

static bool AnchorTF2( Vector &anchor )
{
	TryOpen();
	if ( !s_pShm )
		return false;
	const proto::Header &h = *(const proto::Header *)( s_pShm + proto::kOffHeader );
	const proto::Anchor &a = *(const proto::Anchor *)( s_pShm + proto::kOffAnchor );
	if ( h.magic != proto::kMagic || h.version != proto::kVersion || !a.valid )
		return false;
	anchor.Init( (float)a.x, (float)a.y, (float)a.z );
#ifndef CLIENT_DLL
	// The server may have re-centred before the client noticed: use the server's anchor.
	const proto::Recentre &r = *(const proto::Recentre *)( s_pShm + proto::kOffRecentre );
	anchor.x -= (float)( r.serverX - r.clientX );
	anchor.y -= (float)( r.serverY - r.clientY );
#endif
	return true;
}

void FortCraft_ReportShot( const Vector &src, const Vector &dir, float flDistance, float flDamage,
	unsigned int flags, unsigned int weapon, unsigned int tool, float headshotRange )
{
	Vector anchor;
	if ( !AnchorTF2( anchor ) )
		return;
	const float s = (float)proto::kUnitsPerBlock;
	proto::Shots &shots = *(proto::Shots *)( s_pShm + proto::kOffShots );
	proto::Shot &shot = shots.ring[ shots.count % proto::kMaxShots ];
	// TF2 (x, y north, z up) to Minecraft (x, y up, z south), around the anchor.
	shot.ox = ( src.x - anchor.x ) / s + 0.5f;
	shot.oy = ( src.z - anchor.z ) / s - 60.0f;
	shot.oz = -( src.y - anchor.y ) / s + 0.5f;
	Vector d = dir;
	VectorNormalize( d );
	shot.dx = d.x;
	shot.dy = d.z;
	shot.dz = -d.y;
	shot.range = flDistance / s;
	shot.damage = flDamage;
	shot.flags = flags;
	shot.weapon = weapon;
	shot.tool = tool;
	shot.headshotRange = headshotRange;
	*(volatile uint32_t *)&shots.count = shots.count + 1;
}

void FortCraft_ReportWeaponShot( CTFWeaponBase *weapon, const Vector &src, const Vector &dir,
	float distance, float damage, int damageType, bool melee, bool mini )
{
	unsigned int flags = melee ? FC_MELEE : 0;
	unsigned int tool = FC_GENERIC;
	float headshotRange = 0;
	if ( damageType & DMG_CRITICAL ) flags |= FC_CRIT;
	if ( mini ) flags |= FC_MINICRIT;
	if ( weapon )
	{
		const int id = weapon->GetWeaponID();
		CTFPlayer *owner = weapon->GetTFPlayerOwner();
		if ( weapon->IsCurrentAttackACrit() || ( owner && owner->m_Shared.IsCritBoosted() ) ) flags |= FC_CRIT;
		float flBleed = 0.0f;  // Boston Basher, Tribalman's Shiv, Southern Hospitality...
		CALL_ATTRIB_HOOK_FLOAT_ON_OTHER( weapon, flBleed, bleeding_duration );
		if ( flBleed > 0.0f )
			flags |= (unsigned int)MIN( 255, (int)ceilf( flBleed ) ) << FC_BLEED_SHIFT;
		// Market Gardener and friends: crits while airborne from an explosion (rocket jump).
		int iCritWhileAirborne = 0;
		CALL_ATTRIB_HOOK_INT_ON_OTHER( weapon, iCritWhileAirborne, crit_while_airborne );
		if ( iCritWhileAirborne && owner && owner->InAirDueToExplosion() )
			flags |= FC_CRIT;
		if ( melee )
		{
			const int item = weapon->GetAttributeContainer()->GetItem()->GetItemDefIndex();
			if ( id == TF_WEAPON_SHOVEL ) tool = ( item == 128 || item == 775 ) ? FC_PICKAXE : FC_SHOVEL;
			else if ( id == TF_WEAPON_FIREAXE ) tool = FC_AXE;
			else if ( id == TF_WEAPON_KNIFE || id == TF_WEAPON_SWORD ) tool = FC_BLADE;
			if ( id == TF_WEAPON_KNIFE ) flags |= FC_KNIFE;
		}
		else if ( damageType & DMG_USE_HITLOCATIONS )
		{
			// Mirror headshot eligibility without calling CanFireCriticalShot(true),
			// which mutates the rifle's current attack state.
			CTFSniperRifle *rifle = dynamic_cast<CTFSniperRifle *>( weapon );
			if ( rifle && owner && rifle->GetRifleType() != RIFLE_JARATE )
			{
				int fullCharge = 0, noScope = 0;
				CALL_ATTRIB_HOOK_INT_ON_OTHER( weapon, fullCharge, sniper_no_headshot_without_full_charge );
				CALL_ATTRIB_HOOK_INT_ON_OTHER( weapon, noScope, sniper_crit_no_scope );
				if ( ( !fullCharge || rifle->IsFullyCharged() ) &&
					( noScope || ( owner->GetFOV() < owner->GetDefaultFOV() && gpGlobals->curtime - owner->GetFOVTime() >= 0.2f ) ) )
					flags |= FC_HEADSHOT;
			}
			else if ( id == TF_WEAPON_REVOLVER )
			{
				// Revolver GetDamageType only sets HITLOCATIONS once the Ambassador
				// has recovered its headshot accuracy. Stock revolvers never set it.
				flags |= FC_HEADSHOT;
				headshotRange = 1200.0f / (float)proto::kUnitsPerBlock;
			}
		}
	}
	FortCraft_ReportShot( src, dir, distance, damage, flags, weapon ? weapon->GetWeaponID() : 0, tool, headshotRange );
}

void FortCraft_ReportBlast( const Vector &src, float radius, float damage, int damageType )
{
	Vector anchor;
	if ( !( damageType & DMG_BLAST ) || damage <= 0 || radius <= 0 || !AnchorTF2( anchor ) ) return;
	const float scale = (float)proto::kUnitsPerBlock;
	proto::Explosions &out = *(proto::Explosions *)( s_pShm + proto::kOffExplosions );
	proto::Explosion &e = out.ring[ out.count % proto::kMaxExplosions ];
	e.x = ( src.x - anchor.x ) / scale + 0.5f;
	e.y = ( src.z - anchor.z ) / scale - 60.0f;
	e.z = -( src.y - anchor.y ) / scale + 0.5f;
	e.radius = radius / scale;
	e.damage = damage;
	e.flags = ( damageType & DMG_CRITICAL ) ? FC_CRIT : 0;
	*(volatile uint32_t *)&out.count = out.count + 1;
}

// TF2's fire weapons: tell Minecraft where fire touched the world or a mob. Flamethrower flames
// come many times a second, so those (bFlame) are thinned to about 15 a second.
void FortCraft_ReportFire( const Vector &pos, float flRadius, bool bFlame )
{
	Vector anchor;
	if ( !AnchorTF2( anchor ) )
		return;
	static float s_flNextFlame;
	if ( bFlame )
	{
		if ( gpGlobals->curtime < s_flNextFlame )
			return;
		s_flNextFlame = gpGlobals->curtime + 0.066f;
	}
	const float s = (float)proto::kUnitsPerBlock;
	proto::FirePoints &fires = *(proto::FirePoints *)( s_pShm + proto::kOffFires );
	proto::FirePoint &f = fires.ring[ fires.count % proto::kMaxFires ];
	f.x = ( pos.x - anchor.x ) / s + 0.5f;
	f.y = ( pos.z - anchor.z ) / s - 60.0f;
	f.z = -( pos.y - anchor.y ) / s + 0.5f;
	f.radius = flRadius / s;
	*(volatile uint32_t *)&fires.count = fires.count + 1;
}

// Engineer buildings for Minecraft (hostile mobs next to one attack it), and the hits back.
void FortCraft_WriteBuildings( CBaseEntity **ppObjects, int nCount )
{
	Vector anchor;
	if ( !AnchorTF2( anchor ) )
		return;
	const float s = (float)proto::kUnitsPerBlock;
	proto::Buildings &out = *(proto::Buildings *)( s_pShm + proto::kOffBuildings );
	uint32_t seq = out.seq;
	*(volatile uint32_t *)&out.seq = ( seq + 1 ) | 1;  // odd: writing
	uint32_t n = 0;
	for ( int i = 0; i < nCount && n < proto::kMaxBuildings; ++i )
	{
		CBaseEntity *pObj = ppObjects[ i ];
		if ( !pObj )
			continue;
		const Vector mins = pObj->GetAbsOrigin() + pObj->WorldAlignMins();
		const Vector maxs = pObj->GetAbsOrigin() + pObj->WorldAlignMaxs();
		proto::Building &b = out.list[ n++ ];
		b.ent = pObj->entindex();
		b.type = ( pObj->IsBaseObject() && static_cast< CBaseObject * >( pObj )->GetType() == OBJ_SENTRYGUN ) ? 1 : 0;
		b.minX = ( mins.x - anchor.x ) / s + 0.5f;
		b.maxX = ( maxs.x - anchor.x ) / s + 0.5f;
		b.minY = ( mins.z - anchor.z ) / s - 60.0f;
		b.maxY = ( maxs.z - anchor.z ) / s - 60.0f;
		b.minZ = -( maxs.y - anchor.y ) / s + 0.5f;
		b.maxZ = -( mins.y - anchor.y ) / s + 0.5f;
	}
	out.count = n;
	*(volatile uint32_t *)&out.seq = ( ( seq + 1 ) | 1 ) + 1;  // even: done
}

void FortCraft_ApplyBuildingHits()
{
	Vector anchor;
	if ( !AnchorTF2( anchor ) )
		return;
	const proto::BuildingHits &hits = *(const proto::BuildingHits *)( s_pShm + proto::kOffBuildingHits );
	static uint32_t s_nSeen = 0xFFFFFFFF;
	uint32_t count = *(volatile const uint32_t *)&hits.count;
	if ( s_nSeen == 0xFFFFFFFF || count < s_nSeen || count - s_nSeen > proto::kMaxBuildingHits )
		s_nSeen = count;  // first look, or Minecraft restarted: don't replay old ones
	for ( ; s_nSeen != count; ++s_nSeen )
	{
		const proto::BuildingHit &hit = hits.ring[ s_nSeen % proto::kMaxBuildingHits ];
		CBaseEntity *pObj = UTIL_EntityByIndex( (int)hit.ent );
		if ( !pObj || !pObj->IsBaseObject() || hit.amount <= 0.0f )
			continue;
		// Minecraft's player has 20 health: a mob's hit takes the same share of the building's.
		const float damage = hit.amount * pObj->GetMaxHealth() / 20.0f;
		CTakeDamageInfo info( GetWorldEntity(), GetWorldEntity(), damage, DMG_CLUB );
		info.SetDamagePosition( pObj->WorldSpaceCenter() );
		pObj->TakeDamage( info );
		Msg( "FortCraft: a Minecraft mob hit building %d for %.0f" "\n", (int)hit.ent, damage );
	}
}

// Re-centring: keep the player within TF2's playing field however far they travel in Minecraft.
// Moves every entity whose class name starts with this back by delta (re-centring).
static int ShiftByClassname( const char *pszPrefix, const Vector &delta )
{
	const int nLen = V_strlen( pszPrefix );
	int n = 0;
	for ( CBaseEntity *pEnt = gEntList.FirstEnt(); pEnt; pEnt = gEntList.NextEnt( pEnt ) )
	{
		if ( V_strncmp( pEnt->GetClassname(), pszPrefix, nLen ) != 0 )
			continue;
		Vector p = pEnt->GetAbsOrigin() - delta;
		pEnt->Teleport( &p, NULL, NULL );
		++n;
	}
	return n;
}

void FortCraft_MaybeRecentre( CBaseEntity *pPlayer, CBaseEntity **ppObjects, int nObjects )
{
	Vector anchor;
	if ( !pPlayer || !pPlayer->IsAlive() || !AnchorTF2( anchor ) )
		return;
	proto::Recentre &r = *(proto::Recentre *)( s_pShm + proto::kOffRecentre );
	if ( r.serverX != r.clientX || r.serverY != r.clientY )
		return;  // the client hasn't caught up with the last one yet
	const Vector pos = pPlayer->GetAbsOrigin();
	const float kLimit = 8000.0f;  // ~165 blocks from the middle; the field's edge is ~16300
	if ( fabsf( pos.x ) < kLimit && fabsf( pos.y ) < kLimit )
		return;
	const float s = (float)proto::kUnitsPerBlock;
	const Vector delta( floorf( pos.x / s + 0.5f ) * s, floorf( pos.y / s + 0.5f ) * s, 0.0f );  // whole blocks

	// Everything that lives in Minecraft's world moves back by delta: the player, the buildings,
	// and projectiles in flight.
	Vector newPos = pos - delta;
	pPlayer->Teleport( &newPos, NULL, NULL );
	for ( int i = 0; i < nObjects; ++i )
	{
		if ( ppObjects[ i ] )
		{
			Vector p = ppObjects[ i ]->GetAbsOrigin() - delta;
			ppObjects[ i ]->Teleport( &p, NULL, NULL );
		}
	}
	// Cash lying around stays where it is in Minecraft's world too.
	const int nProjectiles = ShiftByClassname( "tf_projectile", delta ) + ShiftByClassname( "item_currencypack", delta );
	r.serverX += delta.x;
	r.serverY += delta.y;
	Msg( "FortCraft: re-centred TF2 by %.0f %.0f units (%d buildings, %d projectiles); nothing moves in Minecraft" "\n",
		delta.x, delta.y, nObjects, nProjectiles );
}

// TF2's test map has its own hazards (kill zones under the map, push and teleport zones).
// They're invisible in Minecraft and would kill or move you for no visible reason: remove them.
static void RemoveMapHazards()
{
	static const char *kClasses[] = { "trigger_hurt", "trigger_push", "trigger_teleport", "trigger_catapult" };
	int nRemoved = 0;
	for ( int i = 0; i < (int)ARRAYSIZE( kClasses ); ++i )
	{
		CBaseEntity *pEnt = NULL;
		while ( ( pEnt = gEntList.FindEntityByClassname( pEnt, kClasses[ i ] ) ) != NULL )
		{
			UTIL_Remove( pEnt );
			++nRemoved;
		}
	}
	Msg( "FortCraft: removed %d hazard zones from TF2's map\n", nRemoved );
}

static float s_flSpawnGuardUntil = 0.0f;
static bool s_bSpawnGuardNoRoom = false;
static Vector s_vecSpawnHold;
static float s_flSpawnHoldUntil = 0.0f;

// True when Minecraft's blocks (as sent so far) have something under or around these feet:
// within a player's width sideways and from 4 blocks below to 2 above.
static bool GroundUnder( const Vector &feet )
{
	const float s = (float)proto::kUnitsPerBlock;
	for ( int i = 0; i < s_Boxes.Count(); ++i )
	{
		const FortCraftBox &box = s_Boxes[ i ];
		if ( feet.x + 24.0f > box.mins.x && feet.x - 24.0f < box.maxs.x
			&& feet.y + 24.0f > box.mins.y && feet.y - 24.0f < box.maxs.y
			&& box.maxs.z > feet.z - 4.0f * s && box.maxs.z < feet.z + 2.0f * s )
			return true;
	}
	return false;
}

static bool SpawnHullOverlapsBlock( CBaseEntity *pPlayer, const Vector &pos )
{
	const Vector mins = pos + pPlayer->CollisionProp()->OBBMins();
	const Vector maxs = pos + pPlayer->CollisionProp()->OBBMaxs();
	for ( int i = 0; i < s_Boxes.Count(); ++i )
	{
		const FortCraftBox &box = s_Boxes[ i ];
		if ( maxs.x > box.mins.x + 0.5f && mins.x < box.maxs.x - 0.5f
			&& maxs.y > box.mins.y + 0.5f && mins.y < box.maxs.y - 0.5f
			&& maxs.z > box.mins.z + 0.5f && mins.z < box.maxs.z - 0.5f )
			return true;
	}
	return false;
}

static bool s_bWaitingForSpawnPoint;

// Puts TF2's player at a Minecraft position (feet): a spawn point or a Minecraft teleport.
static void PlaceAtMinecraft( CBaseEntity *pPlayer, const Vector &anchor, float x, float y, float z, const char *pszWhy )
{
	const float s = (float)proto::kUnitsPerBlock;
	// Leave clearance for block-face rounding while staying under a two-block ceiling.
	Vector pos( ( x - 0.5f ) * s + anchor.x, -( z - 0.5f ) * s + anchor.y, ( y + 60.0f ) * s + anchor.z + 8.0f );
	// Minecraft's spawn point can be anywhere (a bed far away): if it's outside TF2's usable map,
	// re-centre there first (buildings and projectiles move with the world; see MaybeRecentre).
	const float kLimit = 8000.0f;
	if ( fabsf( pos.x ) >= kLimit || fabsf( pos.y ) >= kLimit )
	{
		const Vector delta( floorf( pos.x / s + 0.5f ) * s, floorf( pos.y / s + 0.5f ) * s, 0.0f );
		const int nObjects = ShiftByClassname( "obj_", delta );
		const int nProjectiles = ShiftByClassname( "tf_projectile", delta ) + ShiftByClassname( "item_currencypack", delta );
		proto::Recentre &r = *(proto::Recentre *)( s_pShm + proto::kOffRecentre );
		r.serverX += delta.x;
		r.serverY += delta.y;
		pos -= delta;
		Msg( "FortCraft %s: far away, re-centred TF2 by %.0f %.0f units (%d buildings, %d projectiles)\n",
			pszWhy, delta.x, delta.y, nObjects, nProjectiles );
	}
	pPlayer->Teleport( &pos, NULL, &vec3_origin );
	s_flSpawnGuardUntil = gpGlobals->curtime + 2.0f;
	// Minecraft may not have the ground there loaded yet: hold the player in place until it is.
	s_vecSpawnHold = pos;
	s_flSpawnHoldUntil = gpGlobals->curtime + 5.0f;
	s_bSpawnGuardNoRoom = false;
	Msg( "FortCraft %s: requested=(%.1f %.1f %.1f) feetOffset=%.2f blocks=%d\n",
		pszWhy, x, y, z, 8.0f / s, s_Boxes.Count() );
}

void FortCraft_OnPlayerSpawn( CBaseEntity *pPlayer )
{
	if ( !pPlayer )
		return;
	Vector anchor;
	if ( !AnchorTF2( anchor ) )
	{
		// The very first spawn comes before TF2's client has set the coordinate anchor (it needs
		// the spawned player for that): place the player as soon as it's set.
		s_bWaitingForSpawnPoint = true;
		return;
	}
	const proto::SpawnPoint &sp = *(const proto::SpawnPoint *)( s_pShm + proto::kOffSpawnPoint );
	if ( !sp.valid )
	{
		// Minecraft hasn't sent a spawn point yet (its world is still loading): place the player
		// as soon as it has (FortCraft_EnsureClearSpawn), instead of leaving them at TF2's own
		// spawn, which is deep underground in a normal Minecraft world.
		s_bWaitingForSpawnPoint = true;
		Msg( "FortCraft spawn: waiting for Minecraft's spawn point\n" );
		return;
	}
	s_bWaitingForSpawnPoint = false;
	PlaceAtMinecraft( pPlayer, anchor, sp.x, sp.y, sp.z, "spawn" );
}

void FortCraft_EnsureClearSpawn( CBaseEntity *pPlayer )
{
	Vector anchorNow;
	// Minecraft moved its player itself (portal, ender pearl, /tp): follow it.
	if ( pPlayer && AnchorTF2( anchorNow ) )
	{
		const proto::McTeleport &t = *(const proto::McTeleport *)( s_pShm + proto::kOffMcTeleport );
		static uint32_t s_nTeleportSeen = 0xFFFFFFFF;
		const uint32_t seq = *(volatile const uint32_t *)&t.seq;
		if ( s_nTeleportSeen == 0xFFFFFFFF || seq < s_nTeleportSeen )
			s_nTeleportSeen = seq;  // first look, or Minecraft restarted
		if ( seq != s_nTeleportSeen )
		{
			s_nTeleportSeen = seq;  // while dead it's dropped: the respawn decides where you go
			if ( pPlayer->IsAlive() )
			{
				PlaceAtMinecraft( pPlayer, anchorNow, t.x, t.y, t.z, "teleport" );
				return;
			}
		}
	}
	if ( s_bWaitingForSpawnPoint && pPlayer && pPlayer->IsAlive() && AnchorTF2( anchorNow )
		&& ( (const proto::SpawnPoint *)( s_pShm + proto::kOffSpawnPoint ) )->valid )
	{
		FortCraft_OnPlayerSpawn( pPlayer );  // Minecraft's world is ready now: go to its spawn
		return;
	}
	if ( pPlayer && pPlayer->IsAlive() && gpGlobals->curtime < s_flSpawnHoldUntil )
	{
		if ( GroundUnder( s_vecSpawnHold ) )
		{
			s_flSpawnHoldUntil = 0.0f;
			s_flSpawnGuardUntil = gpGlobals->curtime + 1.0f;  // now check for overlap
		}
		else
		{
			pPlayer->Teleport( &s_vecSpawnHold, NULL, &vec3_origin );
			return;
		}
	}
	if ( !pPlayer || !pPlayer->IsAlive() || gpGlobals->curtime > s_flSpawnGuardUntil || !FortCraft_Active() )
		return;
	const Vector pos = pPlayer->GetAbsOrigin();
	if ( !SpawnHullOverlapsBlock( pPlayer, pos ) )
		return;
	for ( int lift = 4; lift <= 48 * 8; lift += 4 )
	{
		Vector candidate = pos + Vector( 0, 0, (float)lift );
		if ( !SpawnHullOverlapsBlock( pPlayer, candidate ) )
		{
			pPlayer->Teleport( &candidate, NULL, &vec3_origin );
			Msg( "FortCraft spawn corrected: overlap=1 lift=%.2f blocks boxes=%d\n",
				lift / (float)proto::kUnitsPerBlock, s_Boxes.Count() );
			s_flSpawnGuardUntil = 0.0f;
			return;
		}
	}
	if ( !s_bSpawnGuardNoRoom )
	{
		s_bSpawnGuardNoRoom = true;
		Msg( "FortCraft spawn overlap: no clear space within 8 blocks (boxes=%d)\n", s_Boxes.Count() );
	}
}

// Minecraft hits already applied (or dropped). Hits that arrive while the TF2 player is dead
// are dropped: before, they all landed at once on the fresh player after respawn.
static uint32_t s_nHurtsSeen = 0xFFFFFFFF;
static uint32_t s_nFoodHealsSeen = 0;

static void ApplySupplyPack( CTFPlayer *pPlayer )
{
	proto::PackUse &use = *(proto::PackUse *)( s_pShm + proto::kOffPackUse );
	const uint32_t request = *(volatile const uint32_t *)&use.request;
	static uint32_t s_nSeenRequest;
	if ( request == 0 ) { s_nSeenRequest = 0; return; }
	if ( request == s_nSeenRequest ) return;
	if ( request < s_nSeenRequest ) s_nSeenRequest = 0; // fresh shared mapping
	s_nSeenRequest = request;
	bool ok = false;
	if ( pPlayer->IsAlive() )
	{
		const bool large = use.kind == proto::kLargeHealth || use.kind == proto::kLargeAmmo;
		const float share = large ? 1.0f : 0.2f;
		if ( use.kind == proto::kSmallHealth || use.kind == proto::kLargeHealth )
		{
			// The Health Kit (Alex, 2026-10-10): 100-150 health, no overheal, like a TF2 kit.
			if ( pPlayer->GetHealth() < pPlayer->GetMaxHealth() )
				ok = pPlayer->TakeHealth( large ? RandomInt( 100, 150 ) : MAX( 1, (int)ceilf( pPlayer->GetMaxHealth() * share ) ), DMG_GENERIC ) > 0;
		}
		else if ( use.kind == proto::kSmallAmmo || use.kind == proto::kLargeAmmo )
		{
			for ( int type = TF_AMMO_PRIMARY; type < TF_AMMO_COUNT; ++type )
			{
				const int maximum = pPlayer->GetMaxAmmo( type );
				if ( maximum > 0 && pPlayer->GetAmmoCount( type ) < maximum )
					ok |= pPlayer->GiveAmmo( MAX( 1, (int)ceilf( maximum * share ) ), type, true ) > 0;
			}
		}
	}
	if ( ok )  // TF2's own pickup sounds
		pPlayer->EmitSound( ( use.kind == proto::kSmallHealth || use.kind == proto::kLargeHealth ) ? "HealthKit.Touch" : "AmmoPack.Touch" );
	use.ok = ok ? 1 : 0;
	*(volatile uint32_t *)&use.result = request;
	Msg( "FortCraft pack: request=%u kind=%u applied=%d health=%d/%d\n",
		request, use.kind, ok ? 1 : 0, pPlayer->GetHealth(), pPlayer->GetMaxHealth() );
}

#ifndef CLIENT_DLL
// Minecraft's confirmed hits on mobs, on TF2's server: what TF2 normally does when you damage or
// kill something. Mobs aren't TF2 entities, so TF2 never saw these: sentry kill counts (and with
// them Frontier Justice's revenge crits), Baby Face's Blaster boost, Soda Popper hype.
// Cash lying in the world. TF2 only knows Minecraft's blocks near the player, so a bag resting on
// the ground fell through it once the player walked off and vanished (Alex, 2026-10-10). So a bag
// stops moving once it lands (or, for the dragon's rain, once it reaches the ground Minecraft
// named), and only moves again when it's pulled to the player.
struct DroppedMoney
{
	CHandle< CCurrencyPack > hPack;
	bool bHasGround;      // the dragon's rain: land exactly here
	Vector vecGround;
	float flLandAt;
};
static CUtlVector< DroppedMoney > s_DroppedMoney;

// Drops MvM money as a TF2 cash pack you have to walk over (Alex, 2026-10-10), lasting
// flLifetime seconds before it blinks out.
static void DropMoney( const Vector &pos, int nAmount, float flLifetime )
{
	if ( nAmount <= 0 )
		return;
	QAngle angles( 0.0f, RandomFloat( -180.0f, 180.0f ), 0.0f );
	CCurrencyPack *pPack = dynamic_cast< CCurrencyPack * >( CBaseEntity::CreateNoSpawn( "item_currencypack_custom", pos, angles, NULL ) );
	if ( !pPack )
		return;
	pPack->SetAmount( nAmount );
	pPack->m_flFortCraftLifetime = flLifetime;
	Vector vecVelocity = RandomVector( -1.0f, 1.0f );
	vecVelocity.z = 1.0f;
	VectorNormalize( vecVelocity );
	vecVelocity *= 150.0f;
	DispatchSpawn( pPack );
	pPack->DropSingleInstance( vecVelocity, NULL, 0.0f, 0.0f );
	DroppedMoney d;
	d.hPack = pPack;
	d.bHasGround = false;
	d.vecGround = vec3_origin;
	d.flLandAt = 0.0f;
	s_DroppedMoney.AddToTail( d );
}

static void UpdateDroppedMoney();

// One bag of the dragon's rain: falls from 8 blocks above the ground Minecraft picked, lands there.
static void DropMoneyOnGround( const Vector &ground, int nAmount )
{
	const float s = (float)proto::kUnitsPerBlock;
	QAngle angles( 0.0f, RandomFloat( -180.0f, 180.0f ), 0.0f );
	CCurrencyPack *pPack = dynamic_cast< CCurrencyPack * >( CBaseEntity::CreateNoSpawn( "item_currencypack_custom", ground + Vector( 0, 0, 8.0f * s ), angles, NULL ) );
	if ( !pPack )
		return;
	pPack->SetAmount( nAmount );
	pPack->m_flFortCraftLifetime = 300.0f;
	DispatchSpawn( pPack );
	Vector vecDown( 0.0f, 0.0f, -8.0f * s );  // 8 blocks a second: lands after 1 s
	pPack->DropSingleInstance( vecDown, NULL, 0.0f, 0.0f );
	pPack->SetMoveType( MOVETYPE_FLY );  // no gravity, no bounce: straight down
	pPack->SetAbsVelocity( vecDown );
	DroppedMoney d;
	d.hPack = pPack;
	d.bHasGround = true;
	d.vecGround = ground + Vector( 0, 0, 10.0f );
	d.flLandAt = gpGlobals->curtime + 1.0f;
	s_DroppedMoney.AddToTail( d );
}

static void UpdateDroppedMoney()
{
	for ( int i = s_DroppedMoney.Count() - 1; i >= 0; --i )
	{
		DroppedMoney &d = s_DroppedMoney[ i ];
		CCurrencyPack *pPack = d.hPack.Get();
		if ( !pPack )
		{
			s_DroppedMoney.Remove( i );
			continue;
		}
		if ( pPack->GetMoveType() == MOVETYPE_NONE )
			continue;
		if ( pPack->IsClaimed() )
			continue;  // being pulled to the player
		if ( d.bHasGround && gpGlobals->curtime >= d.flLandAt )
		{
			pPack->SetAbsOrigin( d.vecGround );
			pPack->SetAbsVelocity( vec3_origin );
			pPack->SetMoveType( MOVETYPE_NONE );
		}
		else if ( !d.bHasGround && ( pPack->GetFlags() & FL_ONGROUND ) && pPack->GetAbsVelocity().LengthSqr() < 1.0f )
		{
			pPack->SetMoveType( MOVETYPE_NONE );
		}
	}
}

static uint32_t s_nServerMobHitsSeen = 0xFFFFFFFF;
static void ServerMobHits( CTFPlayer *pPlayer )
{
	const proto::MobHits &hits = *(const proto::MobHits *)( s_pShm + proto::kOffMobHits );
	const uint32_t count = *(volatile const uint32_t *)&hits.count;
	if ( s_nServerMobHitsSeen == 0xFFFFFFFF || count < s_nServerMobHitsSeen || count - s_nServerMobHitsSeen > proto::kMaxMobHits )
		s_nServerMobHitsSeen = count;
	for ( ; s_nServerMobHitsSeen != count; ++s_nServerMobHitsSeen )
	{
		const proto::MobHit &hit = hits.ring[ s_nServerMobHitsSeen % proto::kMaxMobHits ];
		// MvM money for the kill (Minecraft works it out: hostile mobs only, by their health).
		const int nMoney = (int)( hit.source >> proto::kMobHitMoneyShift );
		Vector anchor;
		if ( hit.killed == 1 && nMoney > 0 && AnchorTF2( anchor ) )
		{
			// Dropped at the mob (the hit's x, z and the top of its box), not paid straight away:
			// one bag per $100 (at most 50), so the dragon's $5,000 rains down as a pile. Big
			// rewards last 5 minutes, ordinary ones a minute.
			const float s = (float)proto::kUnitsPerBlock;
			const Vector pos( ( hit.x - 0.5f ) * s + anchor.x, -( hit.z - 0.5f ) * s + anchor.y, ( hit.y + 60.0f ) * s + anchor.z );
			if ( hit.source & proto::kMobHitMoneyLand )
			{
				// One bag of the dragon's rain, onto the ground Minecraft picked (y = that ground).
				const Vector ground( ( hit.x - 0.5f ) * s + anchor.x, -( hit.z - 0.5f ) * s + anchor.y, ( hit.y + 60.0f ) * s + anchor.z );
				DropMoneyOnGround( ground, nMoney );
			}
			else
			{
				// Lots of small bags feel better to pick up (Alex, 2026-10-10): about one per $5,
				// at least 2 and at most 40.
				const int nBags = nMoney < 2 ? 1 : clamp( nMoney / 5, 2, 40 );
				const float flLifetime = nMoney >= 1000 ? 300.0f : 60.0f;
				for ( int i = 0; i < nBags; ++i )
					DropMoney( pos, nMoney / nBags + ( i == 0 ? nMoney % nBags : 0 ), flLifetime );
				Msg( "FortCraft MvM: %s dropped $%d in %d bag(s)\n", ( hit.source & proto::kMobHitMoneyOnly ) ? "Minecraft XP" : "a kill", nMoney, nBags );
			}
		}
		// The weapon's on-kill effects (Powerjack and other heal-on-kill, restore health on
		// kill, speed boost, cloak on kill), as killing a player does. Not for sentry kills.
		if ( hit.killed == 1 && !( hit.source & ( FC_SENTRY | proto::kMobHitMoneyOnly ) ) && pPlayer->IsAlive() && pPlayer->GetActiveTFWeapon() )
		{
			CTakeDamageInfo killInfo( pPlayer, pPlayer, pPlayer->GetActiveTFWeapon(), 1.0f, DMG_GENERIC );
			pPlayer->OnKilledOther_Effects( NULL, killInfo );
		}
		if ( hit.source & FC_SENTRY )
		{
			if ( hit.killed != 1 )
				continue;
			for ( int i = 0; i < pPlayer->GetObjectCount(); ++i )
			{
				CBaseObject *pObj = pPlayer->GetObject( i );
				if ( pObj && pObj->GetType() == OBJ_SENTRYGUN && !pObj->IsDying() )
				{
					pObj->IncrementKills();
					Msg( "FortCraft: sentry killed a Minecraft mob (%d kills)\n", pObj->GetKills() );
					break;
				}
			}
			continue;
		}
		// Mob damage raises TF2's random crit chance like damage to players does (not crit
		// damage itself, as TF2 doesn't count that either).
		if ( !( hit.source & FC_CRIT ) && hit.damage > 0.0f )
			pPlayer->m_Shared.FortCraft_RecordMobDamage( hit.damage, hit.killed == 1 );
		// Baby Face's Blaster: boost from damage dealt (as CTFWeaponBase::ApplyOnHitAttributes).
		int iBoostOnDamage = 0;
		CALL_ATTRIB_HOOK_INT_ON_OTHER( pPlayer, iBoostOnDamage, boost_on_damage );
		if ( iBoostOnDamage && hit.damage > 0.0f )
		{
			static ConVarRef s_PepMax( "tf_scout_hype_pep_max" ), s_PepMinDamage( "tf_scout_hype_pep_min_damage" ), s_PepMod( "tf_scout_hype_pep_mod" );
			const float flMod = s_PepMod.IsValid() && s_PepMod.GetFloat() > 0.0f ? s_PepMod.GetFloat() : 1.0f;
			const float flHype = MIN( s_PepMax.IsValid() ? s_PepMax.GetFloat() : 99.0f,
				pPlayer->m_Shared.GetScoutHypeMeter() + MAX( s_PepMinDamage.IsValid() ? s_PepMinDamage.GetFloat() : 10.0f, hit.damage ) / flMod );
			pPlayer->m_Shared.SetScoutHypeMeter( flHype );
			pPlayer->TeamFortress_SetSpeed();
		}
		CTFWeaponBase *pWeapon = pPlayer->GetActiveTFWeapon();
		if ( pWeapon && hit.damage > 0.0f )
		{
			int iRageOnHit = 0;  // Phlogistinator and friends
			CALL_ATTRIB_HOOK_INT_ON_OTHER( pWeapon, iRageOnHit, rage_on_hit );
			if ( iRageOnHit && ( pPlayer->IsPlayerClass( TF_CLASS_SOLDIER ) || pPlayer->IsPlayerClass( TF_CLASS_PYRO ) ) )
				pPlayer->m_Shared.ModifyRage( iRageOnHit );
			int iHealthOnHit = 0;  // Black Box, Blutsauger...
			CALL_ATTRIB_HOOK_INT_ON_OTHER( pWeapon, iHealthOnHit, add_onhit_addhealth );
			if ( iHealthOnHit > 0 )
				pPlayer->TakeHealth( iHealthOnHit, DMG_GENERIC );
		}
		int iHypeOnDamage = 0;
		CALL_ATTRIB_HOOK_INT_ON_OTHER( pPlayer, iHypeOnDamage, hype_on_damage );
		if ( iHypeOnDamage && hit.damage > 0.0f )
		{
			const float flHype = RemapValClamped( hit.damage, 1.f, 200.f, 1.f, 50.f );
			pPlayer->m_Shared.SetScoutHypeMeter( MIN( 100.f, flHype + pPlayer->m_Shared.GetScoutHypeMeter() ) );
		}
	}
}
#endif

#ifndef CLIENT_DLL
// MvM upgrades at Minecraft's enchanting table (Alex, 2026-10-10). TF2's own Bounty Mode path:
// upgrades forced on, one upgrade station entity to buy through, and the player put in the
// "upgrade zone" (which opens TF2's upgrade screen) when Minecraft says the table was used.
static int s_nUpgradeBookshelves;
static void UpgradeStation( CTFPlayer *pPlayer, const Vector &anchor )
{
	if ( TFGameRules() && !TFGameRules()->GameModeUsesUpgrades() )
	{
		TFGameRules()->ForceEnableUpgrades( 2 );
		Msg( "FortCraft MvM: upgrades on\n" );
	}
	if ( !g_hUpgradeEntity )
	{
		// Never spawned as a trigger: it only has to exist for TF2's purchase code.
		CUpgrades *pStation = dynamic_cast< CUpgrades * >( CreateEntityByName( "func_upgradestation" ) );
		if ( pStation )
		{
			g_hUpgradeEntity = pStation;
			Msg( "FortCraft MvM: upgrade station created\n" );
		}
	}
	static uint32_t s_nSeen = 0xFFFFFFFF;
	static Vector s_vecTable;
	static bool s_bOpenedByTable;
	const proto::UpgradeStation &st = *(const proto::UpgradeStation *)( s_pShm + proto::kOffUpgradeStation );
	const uint32_t seq = *(volatile const uint32_t *)&st.seq;
	if ( s_nSeen == 0xFFFFFFFF || seq < s_nSeen )
		s_nSeen = seq;  // first look, or Minecraft restarted
	if ( seq != s_nSeen )
	{
		s_nSeen = seq;
		if ( pPlayer->IsAlive() && g_hUpgradeEntity )
		{
			const float s = (float)proto::kUnitsPerBlock;
			s_vecTable.Init( ( st.x - 0.5f ) * s + anchor.x, -( st.z - 0.5f ) * s + anchor.y, ( st.y + 60.0f ) * s + anchor.z );
			s_nUpgradeBookshelves = st.bookshelves;
			pPlayer->m_Shared.SetInUpgradeZone( true );
			s_bOpenedByTable = true;
			Msg( "FortCraft MvM: enchanting table used (%d bookshelves, $%d)\n", st.bookshelves, pPlayer->GetCurrency() );
		}
	}
	if ( s_bOpenedByTable && pPlayer->m_Shared.IsInUpgradeZone()
		&& ( !pPlayer->IsAlive() || ( pPlayer->GetAbsOrigin() - s_vecTable ).Length2D() > 6.0f * proto::kUnitsPerBlock ) )
	{
		pPlayer->m_Shared.SetInUpgradeZone( false );
		Msg( "FortCraft MvM: left the enchanting table\n" );
	}
	if ( !pPlayer->m_Shared.IsInUpgradeZone() )
		s_bOpenedByTable = false;

	// Dying loses all the money you carry (upgrades stay); half of it is left where you died
	// for 5 minutes (Alex, 2026-10-10).
	static bool s_bWasAlive;
	const bool bAlive = pPlayer->IsAlive();
	if ( s_bWasAlive && !bAlive && pPlayer->GetCurrency() > 0 )
	{
		const int nLost = pPlayer->GetCurrency();
		pPlayer->RemoveCurrency( nLost );
		DropMoney( pPlayer->GetAbsOrigin() + Vector( 0.0f, 0.0f, 32.0f ), nLost / 2, 300.0f );
		Msg( "FortCraft MvM: died with $%d; $%d left where you died for 5 minutes\n", nLost, nLost / 2 );
	}
	s_bWasAlive = bAlive;
}
#endif

#ifndef CLIENT_DLL
// MvM money and upgrades saved with the Minecraft world (Alex, 2026-10-10). Minecraft sends the
// save when the world opens; once applied, TF2 reports the current money and upgrade history
// every half second and Minecraft writes it back to the world folder when it changes.
static uint32_t s_nMvmRestoreApplied;
static void MvmSaveAndRestore( CTFPlayer *pPlayer )
{
	CUtlVector< CUpgradeInfo > *pHistory = pPlayer->FortCraft_UpgradeHistory();
	if ( !pHistory || !g_hUpgradeEntity )
		return;
	const proto::MvmRestore &in = *(const proto::MvmRestore *)( s_pShm + proto::kOffMvmRestore );
	const uint32_t seq = *(volatile const uint32_t *)&in.seq;
	if ( seq != 0 && seq != s_nMvmRestoreApplied && pPlayer->IsAlive() )
	{
		const int nCurrency = in.currency;
		const uint32_t n = MIN( in.count, proto::kMaxMvmUpgrades );
		CUtlVector< CUpgradeInfo > list;
		for ( uint32_t i = 0; i < n; ++i )
		{
			CUpgradeInfo info;
			info.m_iPlayerClass = in.list[ i ].playerClass;
			info.m_itemDefIndex = (item_definition_index_t)in.list[ i ].itemDef;
			info.m_upgrade = in.list[ i ].upgrade;
			info.m_nCost = in.list[ i ].cost;
			if ( info.m_upgrade >= 0 && info.m_upgrade < g_MannVsMachineUpgrades.m_Upgrades.Count() )
				list.AddToTail( info );
		}
		if ( *(volatile const uint32_t *)&in.seq != seq )
			return;  // Minecraft was mid-write: next tick
		pPlayer->SetCurrency( nCurrency );
		pHistory->RemoveAll();
		pPlayer->GetRefundableUpgrades()->RemoveAll();
		for ( int i = 0; i < list.Count(); ++i )
		{
			pHistory->AddToTail( list[ i ] );
			pPlayer->GetRefundableUpgrades()->AddToTail( list[ i ] );  // + and - work on them any time
		}
		// Re-apply: the player's own upgrades, then each weapon's and wearable's.
		pPlayer->ReapplyPlayerUpgrades();
		for ( int i = 0; i < MAX_WEAPONS; ++i )
		{
			CTFWeaponBase *pWeapon = dynamic_cast< CTFWeaponBase * >( pPlayer->GetWeapon( i ) );
			if ( pWeapon && pWeapon->GetAttributeContainer() )
			{
				pPlayer->ReapplyItemUpgrades( pWeapon->GetAttributeContainer()->GetItem() );
				pWeapon->OnUpgraded();
			}
		}
		for ( int i = 0; i < pPlayer->GetNumWearables(); ++i )
		{
			CEconWearable *pWearable = pPlayer->GetWearable( i );
			if ( pWearable && pWearable->GetAttributeContainer() )
				pPlayer->ReapplyItemUpgrades( pWearable->GetAttributeContainer()->GetItem() );
		}
		s_nMvmRestoreApplied = seq;
		Msg( "FortCraft MvM: restored $%d and %d upgrades from the Minecraft world\n", nCurrency, list.Count() );
	}
	if ( s_nMvmRestoreApplied == 0 )
		return;  // nothing restored yet: don't let Minecraft save an empty state over the world's

	static float s_flNextReport;
	if ( gpGlobals->curtime < s_flNextReport )
		return;
	s_flNextReport = gpGlobals->curtime + 0.5f;
	proto::MvmState &out = *(proto::MvmState *)( s_pShm + proto::kOffMvmState );
	const uint32_t outSeq = out.seq;
	*(volatile uint32_t *)&out.seq = ( outSeq + 1 ) | 1;  // odd: writing
	out.restoreApplied = s_nMvmRestoreApplied;
	out.currency = pPlayer->GetCurrency();
	uint32_t n = 0;
	for ( int i = 0; i < pHistory->Count() && n < proto::kMaxMvmUpgrades; ++i )
	{
		const CUpgradeInfo &info = pHistory->Element( i );
		proto::MvmUpgrade &u = out.list[ n++ ];
		u.playerClass = info.m_iPlayerClass;
		u.itemDef = info.m_itemDefIndex;
		u.upgrade = info.m_upgrade;
		u.cost = info.m_nCost;
	}
	out.count = n;
	*(volatile uint32_t *)&out.seq = ( ( outSeq + 1 ) | 1 ) + 1;  // even: done
}
#endif

void FortCraft_ApplyMinecraftDamage( CBaseEntity *pPlayer )
{
	// This existing server tick also retires the Medic presentation proxy when
	// Minecraft clears its target or the link goes away, even after attack release.
	FortCraft_MedicProxy();
	Vector anchor;
	if ( !pPlayer || !AnchorTF2( anchor ) )
		return;
	ApplySupplyPack( static_cast< CTFPlayer * >( pPlayer ) );
#ifndef CLIENT_DLL
	ServerMobHits( static_cast< CTFPlayer * >( pPlayer ) );
	UpdateDroppedMoney();
	UpgradeStation( static_cast< CTFPlayer * >( pPlayer ), anchor );
	MvmSaveAndRestore( static_cast< CTFPlayer * >( pPlayer ) );
#endif
	if ( !pPlayer->IsAlive() )
	{
		s_nHurtsSeen = ( (const proto::Hurts *)( s_pShm + proto::kOffHurts ) )->count;
		s_nFoodHealsSeen = ( (const proto::FoodHeals *)( s_pShm + proto::kOffFoodHeals ) )->count;
		return;
	}
	static int s_nLastWaterLevel = -1;
	if ( pPlayer->GetWaterLevel() != s_nLastWaterLevel )
	{
		s_nLastWaterLevel = pPlayer->GetWaterLevel();
		Msg( "FortCraft water server: level=%d at %.1f %.1f %.1f\n", s_nLastWaterLevel,
			pPlayer->GetAbsOrigin().x, pPlayer->GetAbsOrigin().y, pPlayer->GetAbsOrigin().z );
	}
	static bool s_bHazardsRemoved;
	if ( !s_bHazardsRemoved )
	{
		s_bHazardsRemoved = true;
		RemoveMapHazards();
	}
	// Minecraft creative mode: god mode (no damage from anything, TF2's own included).
	const proto::HostDisplay &display = *(const proto::HostDisplay *)( s_pShm + proto::kOffHostDisplay );
	const bool bCreative = ( display.flags & proto::kHostCreative ) != 0;
	if ( bCreative != ( ( pPlayer->GetFlags() & FL_GODMODE ) != 0 ) )
	{
		if ( bCreative )
			pPlayer->AddFlag( FL_GODMODE );
		else
			pPlayer->RemoveFlag( FL_GODMODE );
		Msg( "FortCraft: Minecraft %s mode: TF2 god mode %s\n", bCreative ? "creative" : "survival", bCreative ? "on" : "off" );
	}

	const proto::FoodHeals &food = *(const proto::FoodHeals *)( s_pShm + proto::kOffFoodHeals );
	uint32_t foodCount = *(volatile const uint32_t *)&food.count;
	if ( foodCount < s_nFoodHealsSeen || foodCount - s_nFoodHealsSeen > proto::kMaxFoodHeals )
		s_nFoodHealsSeen = foodCount; // mapping restarted or old events; never replay them
	for ( ; s_nFoodHealsSeen != foodCount; ++s_nFoodHealsSeen )
	{
		const int nutrition = food.nutrition[ s_nFoodHealsSeen % proto::kMaxFoodHeals ];
		if ( nutrition <= 0 )
			continue;
		// Since v48 Minecraft sends the TF2 health itself (raw food 5-15, cooked 15-50). Food can
		// overheal up to TF2's buffed maximum, like a Medic; the overheal wears off as usual.
		const int amount = MIN( nutrition, static_cast< CTFPlayer * >( pPlayer )->m_Shared.GetMaxBuffedHealth() - pPlayer->GetHealth() );
		const int gained = amount > 0 ? pPlayer->TakeHealth( amount, DMG_IGNORE_MAXHEALTH ) : 0;
		Msg( "FortCraft food: heal=%d TF2 healed=%d health=%d/%d\n",
			nutrition, gained, pPlayer->GetHealth(), pPlayer->GetMaxHealth() );
	}

	const proto::Hurts &hurts = *(const proto::Hurts *)( s_pShm + proto::kOffHurts );
	uint32_t count = *(volatile const uint32_t *)&hurts.count;
	if ( s_nHurtsSeen == 0xFFFFFFFF || count < s_nHurtsSeen || count - s_nHurtsSeen > proto::kMaxHurts )
		s_nHurtsSeen = count;  // first look, or Minecraft restarted: don't replay old ones
	const float s = (float)proto::kUnitsPerBlock;
	for ( ; s_nHurtsSeen != count; ++s_nHurtsSeen )
	{
		const proto::Hurt &hurt = hurts.ring[ s_nHurtsSeen % proto::kMaxHurts ];
		if ( hurt.amount <= 0.0f )
			continue;
		// Minecraft's player has 20 health: a hit takes the same share of this class's health.
		const float damage = hurt.amount * pPlayer->GetMaxHealth() / 20.0f;
		Vector from( ( hurt.fromX - 0.5f ) * s + anchor.x, -( hurt.fromZ - 0.5f ) * s + anchor.y, ( hurt.fromY + 60.0f ) * s + anchor.z );
		CTakeDamageInfo info( GetWorldEntity(), GetWorldEntity(), damage, DMG_CLUB );
		info.SetDamagePosition( from );
		Vector force = pPlayer->WorldSpaceCenter() - from;
		VectorNormalize( force );
		info.SetDamageForce( force * damage * 50.0f );
		pPlayer->TakeDamage( info );
		Msg( "FortCraft: Minecraft hit the TF2 player for %.1f Minecraft health = %.0f TF2 damage\n", hurt.amount, damage );
	}
}

#endif
