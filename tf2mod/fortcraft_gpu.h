// FortCraft: the overlay on the GPU (TF2 side). Plain Win32 + Direct3D 9, no Source headers, so
// it builds without Source's precompiled header.
//
// TF2's frame (weapon + HUD over a black, then a white background on the next frame) is copied
// on the GPU into two textures; a small pixel shader turns the pair into RGBA with real
// transparency, written into one of two textures shared with Minecraft by handle. Nothing goes
// through the CPU, so TF2 never waits for the GPU.
#pragma once

#include <stdint.h>

// Finds TF2's Direct3D 9 device (once). Call from the render thread; true when ready.
bool FortCraftGpu_Init( void *hTF2Window );

// Copy the current back buffer (the finished frame) into the black (pass 0) or white (pass 1)
// texture. Creates/resizes textures as needed.
void FortCraftGpu_Grab( int pass, int width, int height );

// After the white grab: render the combined overlay into the back shared texture.
void FortCraftGpu_Combine();

// If a combined overlay has finished on the GPU, returns true once and gives which shared
// texture holds it.
bool FortCraftGpu_TakeFinished( uint32_t *pFront );

// Current shared textures (handles valid while 'generation' is unchanged).
void FortCraftGpu_GetShared( uint64_t *pHandle0, uint64_t *pHandle1, uint32_t *pWidth, uint32_t *pHeight, uint32_t *pGeneration );

// Human-readable reason the GPU path isn't available (for the log), or "" when it is.
const char *FortCraftGpu_Error();

// Which Direct3D modules were hooked to find TF2's device, and how many tables (for the log).
const char *FortCraftGpu_HookInfo();

// Stop waiting for TF2's device (put Direct3D's tables back).
void FortCraftGpu_GiveUp();

// True if the GPU path failed after starting (e.g. shared textures couldn't be made).
bool FortCraftGpu_Broken();

// Diagnostic, read-only: logs where Direct3D's device tables live and which pointers in
// shaderapidx9.dll look like a device (see fortcraft_gpu.cpp). Run with -fortcraft_gpu_survey.
void FortCraftGpu_Survey( void ( *pLog )( const char * ) );
