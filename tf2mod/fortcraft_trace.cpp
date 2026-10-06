// FortCraft: every collision check in TF2 sees Minecraft's blocks instead of TF2's map.
//
// TF2's game code asks the engine "what does this line / box hit?" through one object,
// `enginetrace`. While linked to Minecraft we put a thin wrapper in its place: TF2's map (the
// world) is skipped, entities (players, buildings, projectiles) still count, and Minecraft's
// block boxes are added as solid world. That covers everything at once: rockets and grenades,
// bullets, melee, explosion line of sight and splash, spawn checks, "is this outside the
// world" removal. Earlier, only player movement and flying objects had been switched over,
// and Alex's rockets still exploded on TF2's invisible map walls.
//
// Compiled into both TF2 modules (server: the real game; client: prediction and effects).
#include "cbase.h"
#include "engine/IEngineTrace.h"
#include "fortcraft_collision.h"

// memdbgon must be the last include file in a .cpp file!!!
#include "tier0/memdbgon.h"

// Passes the caller's entity rules through, but never lets the engine test TF2's map.
class CFortCraftEntitiesOnlyFilter : public ITraceFilter
{
public:
	explicit CFortCraftEntitiesOnlyFilter( ITraceFilter *pInner ) : m_pInner( pInner ) {}

	virtual bool ShouldHitEntity( IHandleEntity *pEntity, int contentsMask )
	{
		return m_pInner ? m_pInner->ShouldHitEntity( pEntity, contentsMask ) : true;
	}

	virtual TraceType_t GetTraceType() const
	{
		return TRACE_ENTITIES_ONLY;
	}

private:
	ITraceFilter *m_pInner;
};

static bool WorldOnly( ITraceFilter *pFilter )
{
	return pFilter && pFilter->GetTraceType() == TRACE_WORLD_ONLY;
}

// A Ray_t as start point + box relative to it, the form FortCraft_ClipHullTrace takes.
static void ClipRay( const Ray_t &ray, unsigned int fMask, trace_t *pTrace )
{
	if ( !( fMask & CONTENTS_SOLID ) )
		return;
	Vector start = ray.m_Start + ray.m_StartOffset;
	Vector end = start + ray.m_Delta;
	Vector mins = -ray.m_Extents - ray.m_StartOffset;
	Vector maxs = ray.m_Extents - ray.m_StartOffset;
	FortCraft_ClipHullTrace( start, end, mins, maxs, *pTrace );
}

class CFortCraftEngineTrace : public IEngineTrace
{
public:
	IEngineTrace *m_pReal;

	virtual int GetPointContents( const Vector &vecAbsPosition, IHandleEntity **ppEntity )
	{
		if ( !FortCraft_Active() )
			return m_pReal->GetPointContents( vecAbsPosition, ppEntity );
		if ( ppEntity )
			*ppEntity = NULL;
		int contents = FortCraft_PointInBlocks( vecAbsPosition ) ? CONTENTS_SOLID : CONTENTS_EMPTY;
		if ( FortCraft_PointInWater( vecAbsPosition ) )
			contents |= CONTENTS_WATER;
		return contents;
	}

	virtual int GetPointContents_Collideable( ICollideable *pCollide, const Vector &vecAbsPosition )
	{
		return m_pReal->GetPointContents_Collideable( pCollide, vecAbsPosition );
	}

	virtual void ClipRayToEntity( const Ray_t &ray, unsigned int fMask, IHandleEntity *pEnt, trace_t *pTrace )
	{
		m_pReal->ClipRayToEntity( ray, fMask, pEnt, pTrace );
	}

	virtual void ClipRayToCollideable( const Ray_t &ray, unsigned int fMask, ICollideable *pCollide, trace_t *pTrace )
	{
		m_pReal->ClipRayToCollideable( ray, fMask, pCollide, pTrace );
	}

	virtual void TraceRay( const Ray_t &ray, unsigned int fMask, ITraceFilter *pTraceFilter, trace_t *pTrace )
	{
		if ( !FortCraft_Active() )
		{
			m_pReal->TraceRay( ray, fMask, pTraceFilter, pTrace );
			return;
		}
		Vector start = ray.m_Start + ray.m_StartOffset;
		if ( WorldOnly( pTraceFilter ) )
		{
			FortCraft_ClearTrace( start, start + ray.m_Delta, *pTrace );
		}
		else
		{
			CFortCraftEntitiesOnlyFilter filter( pTraceFilter );
			m_pReal->TraceRay( ray, fMask, &filter, pTrace );
		}
		ClipRay( ray, fMask, pTrace );
	}

	virtual void SetupLeafAndEntityListRay( const Ray_t &ray, CTraceListData &traceData )
	{
		m_pReal->SetupLeafAndEntityListRay( ray, traceData );
	}

	virtual void SetupLeafAndEntityListBox( const Vector &vecBoxMin, const Vector &vecBoxMax, CTraceListData &traceData )
	{
		m_pReal->SetupLeafAndEntityListBox( vecBoxMin, vecBoxMax, traceData );
	}

	virtual void TraceRayAgainstLeafAndEntityList( const Ray_t &ray, CTraceListData &traceData, unsigned int fMask, ITraceFilter *pTraceFilter, trace_t *pTrace )
	{
		if ( !FortCraft_Active() )
		{
			m_pReal->TraceRayAgainstLeafAndEntityList( ray, traceData, fMask, pTraceFilter, pTrace );
			return;
		}
		CFortCraftEntitiesOnlyFilter filter( pTraceFilter );
		m_pReal->TraceRayAgainstLeafAndEntityList( ray, traceData, fMask, &filter, pTrace );
		ClipRay( ray, fMask, pTrace );
	}

	virtual void SweepCollideable( ICollideable *pCollide, const Vector &vecAbsStart, const Vector &vecAbsEnd,
		const QAngle &vecAngles, unsigned int fMask, ITraceFilter *pTraceFilter, trace_t *pTrace )
	{
		if ( !FortCraft_Active() )
		{
			m_pReal->SweepCollideable( pCollide, vecAbsStart, vecAbsEnd, vecAngles, fMask, pTraceFilter, pTrace );
			return;
		}
		if ( WorldOnly( pTraceFilter ) )
		{
			FortCraft_ClearTrace( vecAbsStart, vecAbsEnd, *pTrace );
		}
		else
		{
			CFortCraftEntitiesOnlyFilter filter( pTraceFilter );
			m_pReal->SweepCollideable( pCollide, vecAbsStart, vecAbsEnd, vecAngles, fMask, &filter, pTrace );
		}
		if ( pCollide && ( fMask & CONTENTS_SOLID ) )
			FortCraft_ClipHullTrace( vecAbsStart, vecAbsEnd, pCollide->OBBMins(), pCollide->OBBMaxs(), *pTrace );
	}

	virtual void EnumerateEntities( const Ray_t &ray, bool triggers, IEntityEnumerator *pEnumerator )
	{
		m_pReal->EnumerateEntities( ray, triggers, pEnumerator );
	}

	virtual void EnumerateEntities( const Vector &vecAbsMins, const Vector &vecAbsMaxs, IEntityEnumerator *pEnumerator )
	{
		m_pReal->EnumerateEntities( vecAbsMins, vecAbsMaxs, pEnumerator );
	}

	virtual ICollideable *GetCollideable( IHandleEntity *pEntity )
	{
		return m_pReal->GetCollideable( pEntity );
	}

	virtual int GetStatByIndex( int index, bool bClear )
	{
		return m_pReal->GetStatByIndex( index, bClear );
	}

	virtual void GetBrushesInAABB( const Vector &vMins, const Vector &vMaxs, CUtlVector<int> *pOutput, int iContentsMask )
	{
		if ( FortCraft_Active() )
			return;  // TF2's map isn't there
		m_pReal->GetBrushesInAABB( vMins, vMaxs, pOutput, iContentsMask );
	}

	virtual CPhysCollide *GetCollidableFromDisplacementsInAABB( const Vector &vMins, const Vector &vMaxs )
	{
		if ( FortCraft_Active() )
			return NULL;
		return m_pReal->GetCollidableFromDisplacementsInAABB( vMins, vMaxs );
	}

	virtual bool GetBrushInfo( int iBrush, CUtlVector<Vector4D> *pPlanesOut, int *pContentsOut )
	{
		return m_pReal->GetBrushInfo( iBrush, pPlanesOut, pContentsOut );
	}

	virtual bool PointOutsideWorld( const Vector &ptTest )
	{
		// TF2 removes things it thinks have left the map; Minecraft's world is much bigger.
		if ( FortCraft_Active() )
			return false;
		return m_pReal->PointOutsideWorld( ptTest );
	}

	virtual int GetLeafContainingPoint( const Vector &ptTest )
	{
		return m_pReal->GetLeafContainingPoint( ptTest );
	}
};

static CFortCraftEngineTrace s_Trace;

void FortCraft_InstallTraceWrapper()
{
	if ( !enginetrace || enginetrace == &s_Trace )
		return;
	s_Trace.m_pReal = enginetrace;
	enginetrace = &s_Trace;
	Msg( "FortCraft: %s collision checks now see Minecraft's blocks instead of TF2's map (while linked)\n",
#ifdef CLIENT_DLL
		"client"
#else
		"server"
#endif
	);
}
