// FortCraft: TF2's player box size, shared by TF2's game rules and its push formulas.
#pragma once

// Half the width of TF2's player box: 30 units = 0.625 blocks (Valve's is 48 = 1 block). It
// fits a 1-block gap and an open door (13/16 block = 39 units), and it is still a little wider
// than Minecraft's player (0.6 blocks = 28.8 units). Never make it narrower than Minecraft's:
// then Minecraft's box pokes into walls TF2 lets you touch, and the two games fight (the
// 2026-10-04 door fix, 28 units, jittered at every wall).
#define FORTCRAFT_HULL_HALF 15.0f

// TF2's push formulas (rocket jumps, knockback, airblast) divide by the player box's volume.
// Count the narrower box as Valve's 48 x 48 so every push stays Valve's strength; any other
// size (crouched height, buildings, grenades) is used as is.
inline float FortCraft_PushVolume( const Vector &size )
{
	if ( size.x == 2.0f * FORTCRAFT_HULL_HALF && size.y == 2.0f * FORTCRAFT_HULL_HALF )
		return 48.0f * 48.0f * size.z;
	return size.x * size.y * size.z;
}
