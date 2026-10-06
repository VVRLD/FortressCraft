// FortCraft: the TF2 end of the shared-memory link (see protocol/fortcraft_protocol.h).
//
// Minecraft is the host and creates the mapping; this side opens it, takes the keys and look
// direction Minecraft forwards, and reports where the TF2 player is each render frame.
#pragma once

class CUserCmd;

// Called at the top of C_TFPlayer::CreateMove. While Minecraft is linked, replaces the
// command's movement keys and view angles with Minecraft's. Returns true if it did.
bool FortCraft_ApplyInput( CUserCmd *pCmd );

// Called at the end of OnRenderStart (cdll_client_int.cpp), once per TF2 render frame, after
// entities have been interpolated: heartbeat, auto team/class join, and the player position.
void FortCraft_OnRenderStart();

// True while linked to Minecraft.
bool FortCraft_IsLinked();

// True while linked and one of TF2's own menus that takes the mouse is open (class/team menu,
// main menu, loadout). viewrender.cpp then also paints TF2's main-menu layer into the overlay.
bool FortCraft_UiOpen();

// Called after TF2 sets up its main view (view.cpp): while linked, replaces the horizontal field
// of view and aspect ratio with Minecraft's, so the picture lines up with Minecraft's world.
// Also reports the camera TF2 renders with, so Minecraft can follow it in third person (taunts).
class Vector;
class QAngle;
void FortCraft_OverrideView( float &flFov, float &flAspectRatio, const Vector &origin, const QAngle &angles );

// Called right before TF2 draws the viewmodel (viewrender.cpp): blanks the screen and depth so
// only what's drawn next (the weapon and HUD) remains. The blank is black, or white on the
// second frame of a capture pair.
void FortCraft_ClearForOverlay();

// Called after TF2 has drawn its HUD (viewrender.cpp): on capture frames, reads the frame back;
// each black + white pair is combined (on a worker thread) into the overlay for Minecraft.
void FortCraft_ReadOverlay( int width, int height );

// Backpack phase A: Minecraft's item stacks as local-only items on the backpack's last page.
// Both are safe to call for any item id; they return NULL for real (Steam) items.
class CEconItem;
CEconItem *FortCraft_FindLocalItem( unsigned long long itemID );
const char *FortCraft_LocalItemImage( unsigned long long itemID );

// Backpack phase B: true for a Minecraft item; asks Minecraft to hold it instead of TF2's melee.
bool FortCraft_IsLocalItem( unsigned long long itemID );
void FortCraft_EquipLocalItem( unsigned long long itemID );

// Backpack phase C: Minecraft's craftable 2x2 recipes for TF2's crafting screen. The version
// changes whenever Minecraft sends a new list; indexes are valid until then.
unsigned int FortCraft_RecipesVersion();
int FortCraft_RecipeCount();
const char *FortCraft_RecipeName( int i );
const char *FortCraft_RecipeInputs( int i );
void FortCraft_CraftRecipe( int i );

// Crafting screen tiles: the backpack item for a recipe's k-th ingredient (0 if not in the
// backpack), and a preview item id for its result (drawn with the Minecraft icon, never in the
// backpack). Craft results: count of answers so far, and whether the last one crafted.
unsigned long long FortCraft_RecipeIngredientItem( int i, int k );
unsigned long long FortCraft_RecipeResultItem( int i );
unsigned int FortCraft_CraftResults( bool *pbLastOk );

// In tf_hud_account.cpp: TF2's hit sound and floating damage number for a Minecraft mob hit at
// pos (TF2 coordinates). Called by the link for each hit Minecraft reports.
void FortCraft_ShowMobHit( const Vector &pos, int iDamage, bool bKilled );

// Called by View_Render (cdll_client_int.cpp) once per frame: 2 if this frame captures an
// overlay pair (draw the view twice, pass 0 over black then pass 1 over white), else 1 (pass -1).
int FortCraft_OverlayDraws();
void FortCraft_SetOverlayPass( int pass );

// Minecraft's brightness at the player's eyes (0..1; 1 when not linked). C_BaseEntity's colour
// modulation multiplies by it, so TF2's full-bright models get as dark as Minecraft's world.
float FortCraft_WorldLight();
