// FortCraft: the overlay on the GPU (TF2 side). See fortcraft_gpu.h.
//
// Built without Source's headers (and without its precompiled header) so it can include the
// Windows SDK's Direct3D 9 headers directly.
// Linux: Direct3D sharing doesn't exist there (TF2 draws through a D3D9-to-Vulkan layer), so
// the bottom of this file is a stand-in that always says "not available" and TF2 uses the
// read-back overlay path. See docs/DESIGN.md, "Linux port".
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <d3d9.h>
#include <d3dcompiler.h>
#include <stdio.h>
#include "fortcraft_gpu.h"

static IDirect3DDevice9 *s_pDevice;
static char s_szError[ 256 ] = "not started";

// ---- Finding TF2's device directly ---------------------------------------------------------------
// Source keeps its device in a global inside shaderapidx9.dll. Look through that module's
// writable data for a pointer to an object whose function table lives in the Direct3D module
// and that answers to IDirect3DDevice9. Every read is guarded, so a stray value can't crash TF2.

static bool InModule( const void *p, const BYTE *pBase, SIZE_T size )
{
	return (const BYTE *)p >= pBase && (const BYTE *)p < pBase + size;
}

static SIZE_T ModuleSize( HMODULE hModule )
{
	const IMAGE_DOS_HEADER *pDos = (const IMAGE_DOS_HEADER *)hModule;
	const IMAGE_NT_HEADERS *pNt = (const IMAGE_NT_HEADERS *)( (const BYTE *)hModule + pDos->e_lfanew );
	return pNt->OptionalHeader.SizeOfImage;
}

static IDirect3DDevice9 *TryAsDevice( void *pCandidate, const BYTE *pD3DBase, SIZE_T d3dSize )
{
	__try
	{
		void **pVtable = *(void ***)pCandidate;
		if ( !InModule( pVtable, pD3DBase, d3dSize ) )
			return NULL;
		IDirect3DDevice9 *pDevice = NULL;
		if ( SUCCEEDED( ( (IUnknown *)pCandidate )->QueryInterface( __uuidof( IDirect3DDevice9 ), (void **)&pDevice ) ) && pDevice )
			return pDevice;  // AddRef'd by QueryInterface
	}
	__except ( EXCEPTION_EXECUTE_HANDLER )
	{
	}
	return NULL;
}

static IDirect3DDevice9 *FindDeviceInShaderApi( char *pszInfo, size_t infoSize )
{
	HMODULE hShader = GetModuleHandleA( "shaderapidx9.dll" );
	HMODULE hD3D = GetModuleHandleA( "d3d9.dll" );
	if ( !hD3D )
		hD3D = GetModuleHandleA( "dxvk_d3d9.dll" );
	if ( !hShader || !hD3D )
	{
		_snprintf( pszInfo, infoSize, "shaderapidx9.dll or d3d9.dll not loaded" );
		return NULL;
	}
	const BYTE *pD3DBase = (const BYTE *)hD3D;
	const SIZE_T d3dSize = ModuleSize( hD3D );
	const IMAGE_DOS_HEADER *pDos = (const IMAGE_DOS_HEADER *)hShader;
	const IMAGE_NT_HEADERS *pNt = (const IMAGE_NT_HEADERS *)( (const BYTE *)hShader + pDos->e_lfanew );
	const IMAGE_SECTION_HEADER *pSection = IMAGE_FIRST_SECTION( pNt );
	int nChecked = 0;
	for ( int s = 0; s < pNt->FileHeader.NumberOfSections; ++s, ++pSection )
	{
		if ( !( pSection->Characteristics & IMAGE_SCN_MEM_WRITE ) )
			continue;
		void **pBegin = (void **)( (BYTE *)hShader + pSection->VirtualAddress );
		const size_t count = pSection->Misc.VirtualSize / sizeof( void * );
		for ( size_t i = 0; i < count; ++i )
		{
			void *pValue = pBegin[ i ];
			if ( !pValue || ( (uintptr_t)pValue & 7 ) || InModule( pValue, (const BYTE *)hShader, ModuleSize( hShader ) ) )
				continue;
			MEMORY_BASIC_INFORMATION mbi;
			if ( !VirtualQuery( pValue, &mbi, sizeof( mbi ) ) || mbi.State != MEM_COMMIT || ( mbi.Protect & ( PAGE_NOACCESS | PAGE_GUARD ) ) )
				continue;
			++nChecked;
			IDirect3DDevice9 *pDevice = TryAsDevice( pValue, pD3DBase, d3dSize );
			if ( pDevice )
			{
				_snprintf( pszInfo, infoSize, "found in shaderapidx9.dll data (%d pointers checked)", nChecked );
				return pDevice;
			}
			// A one-level-deeper search (2026-10-05) crashed TF2 ("failed to lock index buffer"):
			// calling into Direct3D's internal, non-COM objects isn't safe. Don't go deeper.
		}
	}
	_snprintf( pszInfo, infoSize, "not found in shaderapidx9.dll data (%d pointers checked)", nChecked );
	return NULL;
}

// ---- Finding TF2's device ----------------------------------------------------------------------
// The SDK doesn't hand out Source's Direct3D device on PC. Devices of the same kind share one
// function table, so we make throwaway devices of our own, point their tables' EndScene and
// Present at a tiny hook, and the next call TF2 makes tells us its device; then the tables are
// put back. Direct3D uses different tables for different creation flags (and DXVK, if TF2 uses
// it, has its own), so we cover every combination we can create. First try (only
// software/plain d3d9) caught nothing in Alex's run, hence the wider net.

typedef HRESULT( WINAPI *Direct3DCreate9Ex_t )( UINT, IDirect3D9Ex ** );
typedef HRESULT( STDMETHODCALLTYPE *EndScene_t )( IDirect3DDevice9 * );
typedef HRESULT( STDMETHODCALLTYPE *Present_t )( IDirect3DDevice9 *, const RECT *, const RECT *, HWND, const RGNDATA * );

static const int kPresentSlot = 17;   // IDirect3DDevice9::Present
static const int kEndSceneSlot = 42;  // IDirect3DDevice9::EndScene

struct HookedTable
{
	void **pVtable;
	void *pOriginalPresent;
	void *pOriginalEndScene;
};
static HookedTable s_Tables[ 16 ];
static int s_nTables;
static char s_szHookInfo[ 256 ];

static void PatchSlot( void **pVtable, int slot, void *pFunc )
{
	DWORD oldProtect;
	VirtualProtect( &pVtable[ slot ], sizeof( void * ), PAGE_EXECUTE_READWRITE, &oldProtect );
	pVtable[ slot ] = pFunc;
	VirtualProtect( &pVtable[ slot ], sizeof( void * ), oldProtect, &oldProtect );
}

static HookedTable *TableOf( IDirect3DDevice9 *pDevice )
{
	void **pVtable = *(void ***)pDevice;
	for ( int i = 0; i < s_nTables; ++i )
		if ( s_Tables[ i ].pVtable == pVtable )
			return &s_Tables[ i ];
	return NULL;
}

static void Unhook()
{
	for ( int i = 0; i < s_nTables; ++i )
	{
		PatchSlot( s_Tables[ i ].pVtable, kPresentSlot, s_Tables[ i ].pOriginalPresent );
		PatchSlot( s_Tables[ i ].pVtable, kEndSceneSlot, s_Tables[ i ].pOriginalEndScene );
	}
}

static void Caught( IDirect3DDevice9 *pDevice )
{
	if ( s_pDevice )
		return;
	s_pDevice = pDevice;
	s_pDevice->AddRef();
}

static HRESULT STDMETHODCALLTYPE EndSceneHook( IDirect3DDevice9 *pDevice )
{
	HookedTable *t = TableOf( pDevice );
	EndScene_t pOriginal = (EndScene_t)t->pOriginalEndScene;
	Caught( pDevice );
	return pOriginal( pDevice );
}

static HRESULT STDMETHODCALLTYPE PresentHook( IDirect3DDevice9 *pDevice, const RECT *a, const RECT *b, HWND c, const RGNDATA *d )
{
	HookedTable *t = TableOf( pDevice );
	Present_t pOriginal = (Present_t)t->pOriginalPresent;
	Caught( pDevice );
	return pOriginal( pDevice, a, b, c, d );
}

static void HookModule( const char *pszModule, HWND hWindow )
{
	HMODULE hModule = GetModuleHandleA( pszModule );
	Direct3DCreate9Ex_t pCreate = hModule ? (Direct3DCreate9Ex_t)GetProcAddress( hModule, "Direct3DCreate9Ex" ) : NULL;
	if ( !pCreate )
		return;
	IDirect3D9Ex *pD3D = NULL;
	if ( FAILED( pCreate( D3D_SDK_VERSION, &pD3D ) ) || !pD3D )
		return;

	static const DWORD kFlags[] = {
		D3DCREATE_HARDWARE_VERTEXPROCESSING,
		D3DCREATE_HARDWARE_VERTEXPROCESSING | D3DCREATE_MULTITHREADED,
		D3DCREATE_HARDWARE_VERTEXPROCESSING | D3DCREATE_PUREDEVICE,
		D3DCREATE_SOFTWARE_VERTEXPROCESSING,
		D3DCREATE_SOFTWARE_VERTEXPROCESSING | D3DCREATE_MULTITHREADED,
		D3DCREATE_MIXED_VERTEXPROCESSING,
		D3DCREATE_MIXED_VERTEXPROCESSING | D3DCREATE_MULTITHREADED,
	};
	int nNew = 0;
	for ( int f = 0; f < (int)( sizeof( kFlags ) / sizeof( kFlags[ 0 ] ) ) && s_nTables < 16; ++f )
	{
		D3DPRESENT_PARAMETERS pp = {};
		pp.Windowed = TRUE;
		pp.SwapEffect = D3DSWAPEFFECT_DISCARD;
		pp.BackBufferWidth = 1;
		pp.BackBufferHeight = 1;
		pp.BackBufferFormat = D3DFMT_UNKNOWN;
		pp.hDeviceWindow = hWindow;
		IDirect3DDevice9Ex *pDummy = NULL;
		if ( FAILED( pD3D->CreateDeviceEx( D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, hWindow, kFlags[ f ] | D3DCREATE_NOWINDOWCHANGES | D3DCREATE_FPU_PRESERVE, &pp, NULL, &pDummy ) ) || !pDummy )
			continue;
		void **pVtable = *(void ***)pDummy;
		bool bSeen = false;
		for ( int i = 0; i < s_nTables; ++i )
			bSeen |= s_Tables[ i ].pVtable == pVtable;
		if ( !bSeen )
		{
			HookedTable &t = s_Tables[ s_nTables++ ];
			t.pVtable = pVtable;
			t.pOriginalPresent = pVtable[ kPresentSlot ];
			t.pOriginalEndScene = pVtable[ kEndSceneSlot ];
			PatchSlot( pVtable, kPresentSlot, (void *)PresentHook );
			PatchSlot( pVtable, kEndSceneSlot, (void *)EndSceneHook );
			++nNew;
		}
		pDummy->Release();
	}
	pD3D->Release();

	// Plain (non-Ex) devices have tables of their own too.
	typedef IDirect3D9 *( WINAPI * Direct3DCreate9_t )( UINT );
	Direct3DCreate9_t pCreate9 = (Direct3DCreate9_t)GetProcAddress( hModule, "Direct3DCreate9" );
	IDirect3D9 *pD3D9 = pCreate9 ? pCreate9( D3D_SDK_VERSION ) : NULL;
	for ( int f = 0; pD3D9 && f < (int)( sizeof( kFlags ) / sizeof( kFlags[ 0 ] ) ) && s_nTables < 16; ++f )
	{
		D3DPRESENT_PARAMETERS pp = {};
		pp.Windowed = TRUE;
		pp.SwapEffect = D3DSWAPEFFECT_DISCARD;
		pp.BackBufferWidth = 1;
		pp.BackBufferHeight = 1;
		pp.hDeviceWindow = hWindow;
		IDirect3DDevice9 *pDummy = NULL;
		if ( FAILED( pD3D9->CreateDevice( D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, hWindow, kFlags[ f ] | D3DCREATE_NOWINDOWCHANGES | D3DCREATE_FPU_PRESERVE, &pp, &pDummy ) ) || !pDummy )
			continue;
		void **pVtable = *(void ***)pDummy;
		bool bSeen = false;
		for ( int i = 0; i < s_nTables; ++i )
			bSeen |= s_Tables[ i ].pVtable == pVtable;
		if ( !bSeen )
		{
			HookedTable &t = s_Tables[ s_nTables++ ];
			t.pVtable = pVtable;
			t.pOriginalPresent = pVtable[ kPresentSlot ];
			t.pOriginalEndScene = pVtable[ kEndSceneSlot ];
			PatchSlot( pVtable, kPresentSlot, (void *)PresentHook );
			PatchSlot( pVtable, kEndSceneSlot, (void *)EndSceneHook );
			++nNew;
		}
		pDummy->Release();
	}
	if ( pD3D9 )
		pD3D9->Release();

	size_t len = strlen( s_szHookInfo );
	_snprintf( s_szHookInfo + len, sizeof( s_szHookInfo ) - len, "%s%s: %d table(s)", len ? ", " : "", pszModule, nNew );
}

static bool HookForDevice( void *hWindow )
{
	if ( !hWindow )
		hWindow = GetDesktopWindow();
	HookModule( "d3d9.dll", (HWND)hWindow );
	HookModule( "dxvk_d3d9.dll", (HWND)hWindow );
	if ( !s_nTables )
	{
		_snprintf( s_szError, sizeof( s_szError ), "no Direct3D 9Ex module to hook (%s)", s_szHookInfo[ 0 ] ? s_szHookInfo : "none loaded" );
		return false;
	}
	_snprintf( s_szError, sizeof( s_szError ), "waiting for TF2's device" );
	return true;
}

const char *FortCraftGpu_HookInfo()
{
	return s_szHookInfo;
}

// ---- Resources ---------------------------------------------------------------------------------

static IDirect3DTexture9 *s_pGrab[ 2 ];    // black, white
static IDirect3DTexture9 *s_pShared[ 2 ];  // shared with Minecraft
static HANDLE s_hShared[ 2 ];
static UINT s_nWidth, s_nHeight;
static uint32_t s_nGeneration;
static IDirect3DPixelShader9 *s_pShader;
static IDirect3DStateBlock9 *s_pState;
static IDirect3DQuery9 *s_pQuery;
static int s_nPendingFront = -1;  // shared texture being rendered, waiting on s_pQuery
static uint32_t s_nBack;

static void ReleaseTextures()
{
	for ( int i = 0; i < 2; ++i )
	{
		if ( s_pGrab[ i ] ) s_pGrab[ i ]->Release();
		if ( s_pShared[ i ] ) s_pShared[ i ]->Release();
		s_pGrab[ i ] = s_pShared[ i ] = NULL;
		s_hShared[ i ] = NULL;
	}
	s_nWidth = s_nHeight = 0;
	s_nPendingFront = -1;
}

static bool s_bBroken;
static bool CreateTexturesInner( UINT w, UINT h );

bool FortCraftGpu_Broken()
{
	return s_bBroken;
}

static bool CreateTextures( UINT w, UINT h )
{
	if ( !CreateTexturesInner( w, h ) )
	{
		s_bBroken = true;  // the link code falls back to the read-back path
		return false;
	}
	return true;
}

static bool CreateTexturesInner( UINT w, UINT h )
{
	ReleaseTextures();
	for ( int i = 0; i < 2; ++i )
	{
		if ( FAILED( s_pDevice->CreateTexture( w, h, 1, D3DUSAGE_RENDERTARGET, D3DFMT_X8R8G8B8, D3DPOOL_DEFAULT, &s_pGrab[ i ], NULL ) ) )
		{
			_snprintf( s_szError, sizeof( s_szError ), "CreateTexture (grab %dx%d) failed", w, h );
			ReleaseTextures();
			return false;
		}
		HANDLE hShared = NULL;
		if ( FAILED( s_pDevice->CreateTexture( w, h, 1, D3DUSAGE_RENDERTARGET, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, &s_pShared[ i ], &hShared ) ) || !hShared )
		{
			_snprintf( s_szError, sizeof( s_szError ), "CreateTexture (shared %dx%d) failed", w, h );
			ReleaseTextures();
			return false;
		}
		s_hShared[ i ] = hShared;
	}
	s_nWidth = w;
	s_nHeight = h;
	++s_nGeneration;
	return true;
}

static const char kShaderSource[] =
	"sampler Black : register(s0);\n"
	"sampler White : register(s1);\n"
	"float4 main( float2 uv : TEXCOORD0 ) : COLOR\n"
	"{\n"
	"	float3 b = tex2D( Black, uv ).rgb;\n"
	"	float3 w = tex2D( White, uv ).rgb;\n"
	// over black = colour * alpha; over white = colour * alpha + (1 - alpha)
	"	float alpha = saturate( 1.0 - max( w.r - b.r, max( w.g - b.g, w.b - b.b ) ) );\n"
	// Premultiplied: the colour over black IS colour * alpha. Dividing by alpha (straight alpha)
	// turned glowing, additive effects (unusuals, muzzle flashes) white and faint (2026-10-10).
	"	float3 colour = saturate( b );\n"
	"	return float4( colour, alpha );\n"
	"}\n";

typedef HRESULT( WINAPI *D3DCompile_t )( LPCVOID, SIZE_T, LPCSTR, const D3D_SHADER_MACRO *, ID3DInclude *, LPCSTR, LPCSTR, UINT, UINT, ID3DBlob **, ID3DBlob ** );

static bool CreateShader()
{
	HMODULE hCompiler = LoadLibraryA( "d3dcompiler_47.dll" );
	D3DCompile_t pCompile = hCompiler ? (D3DCompile_t)GetProcAddress( hCompiler, "D3DCompile" ) : NULL;
	if ( !pCompile )
	{
		_snprintf( s_szError, sizeof( s_szError ), "d3dcompiler_47.dll not found" );
		return false;
	}
	ID3DBlob *pCode = NULL, *pErrors = NULL;
	HRESULT hr = pCompile( kShaderSource, sizeof( kShaderSource ) - 1, "fortcraft_overlay", NULL, NULL, "main", "ps_2_0", 0, 0, &pCode, &pErrors );
	if ( FAILED( hr ) || !pCode )
	{
		_snprintf( s_szError, sizeof( s_szError ), "shader compile failed: %s", pErrors ? (const char *)pErrors->GetBufferPointer() : "?" );
		if ( pErrors ) pErrors->Release();
		return false;
	}
	hr = s_pDevice->CreatePixelShader( (const DWORD *)pCode->GetBufferPointer(), &s_pShader );
	pCode->Release();
	if ( pErrors ) pErrors->Release();
	if ( FAILED( hr ) )
	{
		_snprintf( s_szError, sizeof( s_szError ), "CreatePixelShader failed (0x%08lX)", hr );
		return false;
	}
	return true;
}

static IDirect3DDevice9 *FindDeviceByTable( char *pszInfo, size_t infoSize );

bool FortCraftGpu_Init( void *hTF2Window )
{
	static int s_nState = 0;  // 0 not started, 1 waiting for device, 2 ready, 3 failed
	if ( s_nState == 0 )
	{
		// First, look for the device where Source keeps it; hooking is only the fallback.
		char szFound[ 128 ];
		IDirect3DDevice9 *pFound = FindDeviceByTable( szFound, sizeof( szFound ) );
		if ( !pFound )
			pFound = FindDeviceInShaderApi( szFound, sizeof( szFound ) );
		_snprintf( s_szHookInfo, sizeof( s_szHookInfo ), "device %s", szFound );
		if ( pFound )
		{
			s_pDevice = pFound;  // keeps the reference QueryInterface added
			s_nState = 1;
		}
		else
		{
			s_nState = HookForDevice( hTF2Window ) ? 1 : 3;
		}
	}
	if ( s_nState == 1 && s_pDevice )
	{
		Unhook();  // caught it; put Direct3D's tables back
		// Sharing textures by handle needs a Direct3D 9Ex device.
		IDirect3DDevice9Ex *pEx = NULL;
		if ( FAILED( s_pDevice->QueryInterface( __uuidof( IDirect3DDevice9Ex ), (void **)&pEx ) ) || !pEx )
		{
			_snprintf( s_szError, sizeof( s_szError ), "TF2's device is plain Direct3D 9, not 9Ex: can't share textures" );
			s_nState = 3;
			return false;
		}
		pEx->Release();
		if ( CreateShader()
			&& SUCCEEDED( s_pDevice->CreateStateBlock( D3DSBT_ALL, &s_pState ) )
			&& SUCCEEDED( s_pDevice->CreateQuery( D3DQUERYTYPE_EVENT, &s_pQuery ) ) )
		{
			s_nState = 2;
			s_szError[ 0 ] = 0;
		}
		else
		{
			if ( !s_szError[ 0 ] || strcmp( s_szError, "waiting for TF2's device" ) == 0 )
				_snprintf( s_szError, sizeof( s_szError ), "state block / query creation failed" );
			s_nState = 3;
		}
	}
	return s_nState == 2;
}

void FortCraftGpu_GiveUp()
{
	if ( !s_pDevice )
		Unhook();
}

const char *FortCraftGpu_Error()
{
	return s_szError;
}

void FortCraftGpu_Grab( int pass, int width, int height )
{
	if ( !s_pDevice || pass < 0 || pass > 1 )
		return;
	if ( (UINT)width != s_nWidth || (UINT)height != s_nHeight )
	{
		if ( pass != 0 || !CreateTextures( width, height ) )
			return;
	}
	IDirect3DSurface9 *pBack = NULL, *pDest = NULL;
	if ( SUCCEEDED( s_pDevice->GetRenderTarget( 0, &pBack ) ) && SUCCEEDED( s_pGrab[ pass ]->GetSurfaceLevel( 0, &pDest ) ) )
		s_pDevice->StretchRect( pBack, NULL, pDest, NULL, D3DTEXF_NONE );
	if ( pDest ) pDest->Release();
	if ( pBack ) pBack->Release();
}

struct QuadVertex
{
	float x, y, z, rhw;
	float u, v;
};

void FortCraftGpu_Combine()
{
	if ( !s_pDevice || !s_nWidth || s_nPendingFront >= 0 )
		return;  // not ready, or the previous combine hasn't finished on the GPU yet

	IDirect3DSurface9 *pOldTarget = NULL, *pOldDepth = NULL, *pTarget = NULL;
	s_pDevice->GetRenderTarget( 0, &pOldTarget );
	s_pDevice->GetDepthStencilSurface( &pOldDepth );
	s_pState->Capture();

	s_pShared[ s_nBack ]->GetSurfaceLevel( 0, &pTarget );
	s_pDevice->SetRenderTarget( 0, pTarget );
	for ( DWORD i = 1; i < 4; ++i )
		s_pDevice->SetRenderTarget( i, NULL );
	s_pDevice->SetDepthStencilSurface( NULL );
	D3DVIEWPORT9 vp = { 0, 0, s_nWidth, s_nHeight, 0.0f, 1.0f };
	s_pDevice->SetViewport( &vp );

	s_pDevice->SetRenderState( D3DRS_ZENABLE, FALSE );
	s_pDevice->SetRenderState( D3DRS_ZWRITEENABLE, FALSE );
	s_pDevice->SetRenderState( D3DRS_ALPHABLENDENABLE, FALSE );
	s_pDevice->SetRenderState( D3DRS_ALPHATESTENABLE, FALSE );
	s_pDevice->SetRenderState( D3DRS_STENCILENABLE, FALSE );
	s_pDevice->SetRenderState( D3DRS_SCISSORTESTENABLE, FALSE );
	s_pDevice->SetRenderState( D3DRS_CULLMODE, D3DCULL_NONE );
	s_pDevice->SetRenderState( D3DRS_COLORWRITEENABLE, 0xF );
	s_pDevice->SetRenderState( D3DRS_SRGBWRITEENABLE, FALSE );
	s_pDevice->SetRenderState( D3DRS_FOGENABLE, FALSE );
	s_pDevice->SetRenderState( D3DRS_CLIPPLANEENABLE, 0 );
	for ( DWORD s = 0; s < 2; ++s )
	{
		s_pDevice->SetTexture( s, s_pGrab[ s ] );
		s_pDevice->SetSamplerState( s, D3DSAMP_MINFILTER, D3DTEXF_POINT );
		s_pDevice->SetSamplerState( s, D3DSAMP_MAGFILTER, D3DTEXF_POINT );
		s_pDevice->SetSamplerState( s, D3DSAMP_MIPFILTER, D3DTEXF_NONE );
		s_pDevice->SetSamplerState( s, D3DSAMP_ADDRESSU, D3DTADDRESS_CLAMP );
		s_pDevice->SetSamplerState( s, D3DSAMP_ADDRESSV, D3DTADDRESS_CLAMP );
		s_pDevice->SetSamplerState( s, D3DSAMP_SRGBTEXTURE, FALSE );
	}
	s_pDevice->SetVertexShader( NULL );
	s_pDevice->SetPixelShader( s_pShader );
	s_pDevice->SetFVF( D3DFVF_XYZRHW | D3DFVF_TEX1 );

	// Full-screen quad; -0.5 lines texels up with pixels in Direct3D 9.
	float w = (float)s_nWidth - 0.5f, h = (float)s_nHeight - 0.5f;
	QuadVertex quad[ 4 ] = {
		{ -0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 0.0f },
		{ w, -0.5f, 0.0f, 1.0f, 1.0f, 0.0f },
		{ -0.5f, h, 0.0f, 1.0f, 0.0f, 1.0f },
		{ w, h, 0.0f, 1.0f, 1.0f, 1.0f },
	};
	s_pDevice->DrawPrimitiveUP( D3DPT_TRIANGLESTRIP, 2, quad, sizeof( QuadVertex ) );

	s_pQuery->Issue( D3DISSUE_END );
	s_nPendingFront = (int)s_nBack;
	s_nBack ^= 1;

	s_pState->Apply();
	s_pDevice->SetRenderTarget( 0, pOldTarget );
	s_pDevice->SetDepthStencilSurface( pOldDepth );
	if ( pTarget ) pTarget->Release();
	if ( pOldTarget ) pOldTarget->Release();
	if ( pOldDepth ) pOldDepth->Release();
}

bool FortCraftGpu_TakeFinished( uint32_t *pFront )
{
	if ( !s_pDevice || s_nPendingFront < 0 )
		return false;
	// Non-blocking: has the GPU finished the combine? (D3DGETDATA_FLUSH makes sure it was sent.)
	if ( s_pQuery->GetData( NULL, 0, D3DGETDATA_FLUSH ) != S_OK )
		return false;
	*pFront = (uint32_t)s_nPendingFront;
	s_nPendingFront = -1;
	return true;
}

void FortCraftGpu_GetShared( uint64_t *pHandle0, uint64_t *pHandle1, uint32_t *pWidth, uint32_t *pHeight, uint32_t *pGeneration )
{
	*pHandle0 = (uint64_t)(uintptr_t)s_hShared[ 0 ];
	*pHandle1 = (uint64_t)(uintptr_t)s_hShared[ 1 ];
	*pWidth = s_nWidth;
	*pHeight = s_nHeight;
	*pGeneration = s_nGeneration;
}

// ---- Survey (diagnostic, read-only) ------------------------------------------------------------
// Why isn't TF2's device found? Logs where our own throwaway devices' function tables live
// (inside d3d9.dll or not), and the pointers in shaderapidx9.dll's data (and one level into
// the objects they point at) whose function table looks like a device's: most of its first 119
// entries equal those of one of our devices. Only reads memory, every read guarded; never calls
// into a candidate (calling into Direct3D's internal objects crashed TF2 once).

static bool SafeReadPtr( const void *pAddress, void **pOut )
{
	__try
	{
		*pOut = *(void *const *)pAddress;
		return true;
	}
	__except ( EXCEPTION_EXECUTE_HANDLER )
	{
		return false;
	}
}

static bool Readable( const void *p )
{
	MEMORY_BASIC_INFORMATION mbi;
	return p && !( (uintptr_t)p & 7 ) && VirtualQuery( p, &mbi, sizeof( mbi ) ) && mbi.State == MEM_COMMIT
		&& !( mbi.Protect & ( PAGE_NOACCESS | PAGE_GUARD ) );
}

static void **s_pSurveyTables[ 16 ];
static IDirect3DDevice9 *s_pSurveyDevices[ 16 ];
static int s_nSurveyTables;

static void SurveyMakeTables( HWND hWindow )
{
	HMODULE hModule = GetModuleHandleA( "d3d9.dll" );
	Direct3DCreate9Ex_t pCreate = hModule ? (Direct3DCreate9Ex_t)GetProcAddress( hModule, "Direct3DCreate9Ex" ) : NULL;
	IDirect3D9Ex *pD3D = NULL;
	if ( !pCreate || FAILED( pCreate( D3D_SDK_VERSION, &pD3D ) ) || !pD3D )
		return;
	static const DWORD kFlags[] = {
		D3DCREATE_HARDWARE_VERTEXPROCESSING, D3DCREATE_HARDWARE_VERTEXPROCESSING | D3DCREATE_MULTITHREADED,
		D3DCREATE_SOFTWARE_VERTEXPROCESSING, D3DCREATE_MIXED_VERTEXPROCESSING,
	};
	for ( int f = 0; f < 4 && s_nSurveyTables < 16; ++f )
	{
		for ( int ex = 0; ex < 2 && s_nSurveyTables < 16; ++ex )
		{
			D3DPRESENT_PARAMETERS pp = {};
			pp.Windowed = TRUE;
			pp.SwapEffect = D3DSWAPEFFECT_DISCARD;
			pp.BackBufferWidth = 1;
			pp.BackBufferHeight = 1;
			pp.hDeviceWindow = hWindow;
			IDirect3DDevice9 *pDummy = NULL;
			HRESULT hr = ex ? pD3D->CreateDeviceEx( D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, hWindow, kFlags[ f ] | D3DCREATE_NOWINDOWCHANGES | D3DCREATE_FPU_PRESERVE, &pp, NULL, (IDirect3DDevice9Ex **)&pDummy )
				: pD3D->CreateDevice( D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, hWindow, kFlags[ f ] | D3DCREATE_NOWINDOWCHANGES | D3DCREATE_FPU_PRESERVE, &pp, &pDummy );
			if ( FAILED( hr ) || !pDummy )
				continue;
			void **pVtable = *(void ***)pDummy;
			bool bSeen = false;
			for ( int i = 0; i < s_nSurveyTables; ++i )
				bSeen |= s_pSurveyTables[ i ] == pVtable;
			if ( !bSeen )
			{
				s_pSurveyTables[ s_nSurveyTables ] = pVtable;
				s_pSurveyDevices[ s_nSurveyTables++ ] = pDummy;  // kept alive: tables are per device
			}
			else
				pDummy->Release();
		}
	}
	pD3D->Release();
}

// How many of the first 119 entries of this table match our closest device table.
static int SurveyScore( void *pObject )
{
	void *pVtable;
	if ( !Readable( pObject ) || !SafeReadPtr( pObject, &pVtable ) || !Readable( pVtable ) )
		return 0;
	int best = 0;
	for ( int t = 0; t < s_nSurveyTables; ++t )
	{
		int same = 0;
		for ( int slot = 0; slot < 119; ++slot )
		{
			void *a;
			if ( !SafeReadPtr( (void **)pVtable + slot, &a ) )
				break;
			same += a == s_pSurveyTables[ t ][ slot ];
		}
		best = same > best ? same : best;
	}
	return best;
}

void FortCraftGpu_Survey( void ( *pLog )( const char * ) )
{
	char line[ 512 ];
	SurveyMakeTables( GetDesktopWindow() );
	HMODULE hD3D = GetModuleHandleA( "d3d9.dll" );
	HMODULE hShader = GetModuleHandleA( "shaderapidx9.dll" );
	const BYTE *pD3DBase = (const BYTE *)hD3D;
	const SIZE_T d3dSize = hD3D ? ModuleSize( hD3D ) : 0;
	for ( int t = 0; t < s_nSurveyTables; ++t )
	{
		_snprintf( line, sizeof( line ), "FortCraft GPU survey: our device table %d at %p (%s d3d9.dll)\n", t, s_pSurveyTables[ t ],
			InModule( s_pSurveyTables[ t ], pD3DBase, d3dSize ) ? "inside" : "OUTSIDE" );
		pLog( line );
	}
	if ( !hShader || !s_nSurveyTables )
	{
		pLog( "FortCraft GPU survey: no shaderapidx9.dll or no device tables\n" );
		return;
	}
	const SIZE_T shaderSize = ModuleSize( hShader );
	const IMAGE_DOS_HEADER *pDos = (const IMAGE_DOS_HEADER *)hShader;
	const IMAGE_NT_HEADERS *pNt = (const IMAGE_NT_HEADERS *)( (const BYTE *)hShader + pDos->e_lfanew );
	const IMAGE_SECTION_HEADER *pSection = IMAGE_FIRST_SECTION( pNt );
	int nFound = 0, nLevel0 = 0, nLevel1 = 0;
	for ( int sct = 0; sct < pNt->FileHeader.NumberOfSections; ++sct, ++pSection )
	{
		if ( !( pSection->Characteristics & IMAGE_SCN_MEM_WRITE ) )
			continue;
		void **pBegin = (void **)( (BYTE *)hShader + pSection->VirtualAddress );
		const size_t count = pSection->Misc.VirtualSize / sizeof( void * );
		for ( size_t i = 0; i < count && nFound < 12; ++i )
		{
			void *pValue = pBegin[ i ];
			if ( !Readable( pValue ) || InModule( pValue, (const BYTE *)hShader, shaderSize ) )
				continue;
			++nLevel0;
			int score = SurveyScore( pValue );
			if ( score >= 80 )
			{
				_snprintf( line, sizeof( line ), "FortCraft GPU survey: level 0 data+0x%zx -> %p score %d/119\n", i * sizeof( void * ), pValue, score );
				pLog( line );
				++nFound;
			}
			// One level deeper: the first 2 KB of the object it points at.
			for ( int k = 0; k < 256 && nFound < 12; ++k )
			{
				void *pInner;
				if ( !SafeReadPtr( (void **)pValue + k, &pInner ) || !Readable( pInner ) )
					continue;
				++nLevel1;
				int score1 = SurveyScore( pInner );
				if ( score1 >= 80 )
				{
					_snprintf( line, sizeof( line ), "FortCraft GPU survey: level 1 data+0x%zx -> %p +0x%x -> %p score %d/119\n",
						i * sizeof( void * ), pValue, k * 8, pInner, score1 );
					pLog( line );
					++nFound;
				}
			}
		}
	}
	for ( int t = 0; t < s_nSurveyTables; ++t )
		s_pSurveyDevices[ t ]->Release();
	s_nSurveyTables = 0;
	_snprintf( line, sizeof( line ), "FortCraft GPU survey: done, %d candidates (%d level-0, %d level-1 pointers looked at)\n", nFound, nLevel0, nLevel1 );
	pLog( line );
}

// The way that works (found with the survey, 2026-10-05): Direct3D gives every device its own
// function table on the heap (so patching our devices' tables never touched TF2's, and the old
// "table inside d3d9.dll" test never matched). But the entries are the same functions: TF2's
// device is the pointer in shaderapidx9.dll's data whose table matches one of our own live
// throwaway devices' tables entry for entry. Only reads until it's certain; then QueryInterface.
static IDirect3DDevice9 *FindDeviceByTable( char *pszInfo, size_t infoSize )
{
	SurveyMakeTables( GetDesktopWindow() );
	HMODULE hShader = GetModuleHandleA( "shaderapidx9.dll" );
	IDirect3DDevice9 *pResult = NULL;
	int nChecked = 0, bestScore = 0;
	if ( hShader && s_nSurveyTables )
	{
		const SIZE_T shaderSize = ModuleSize( hShader );
		const IMAGE_DOS_HEADER *pDos = (const IMAGE_DOS_HEADER *)hShader;
		const IMAGE_NT_HEADERS *pNt = (const IMAGE_NT_HEADERS *)( (const BYTE *)hShader + pDos->e_lfanew );
		const IMAGE_SECTION_HEADER *pSection = IMAGE_FIRST_SECTION( pNt );
		for ( int sct = 0; sct < pNt->FileHeader.NumberOfSections && !pResult; ++sct, ++pSection )
		{
			if ( !( pSection->Characteristics & IMAGE_SCN_MEM_WRITE ) )
				continue;
			void **pBegin = (void **)( (BYTE *)hShader + pSection->VirtualAddress );
			const size_t count = pSection->Misc.VirtualSize / sizeof( void * );
			for ( size_t i = 0; i < count && !pResult; ++i )
			{
				void *pValue = pBegin[ i ];
				if ( !Readable( pValue ) || InModule( pValue, (const BYTE *)hShader, shaderSize ) )
					continue;
				++nChecked;
				const int score = SurveyScore( pValue );
				bestScore = score > bestScore ? score : bestScore;
				if ( score >= 115 )
				{
					IDirect3DDevice9 *pDevice = NULL;
					if ( SUCCEEDED( ( (IUnknown *)pValue )->QueryInterface( __uuidof( IDirect3DDevice9 ), (void **)&pDevice ) ) && pDevice )
						pResult = pDevice;  // AddRef'd by QueryInterface
				}
			}
		}
	}
	for ( int t = 0; t < s_nSurveyTables; ++t )
		s_pSurveyDevices[ t ]->Release();
	s_nSurveyTables = 0;
	_snprintf( pszInfo, infoSize, pResult ? "found by its function table (%d pointers checked)" : "not found by function table (%d pointers checked, best match %d/119)",
		nChecked, bestScore );
	return pResult;
}

#else  // !_WIN32: no GPU sharing yet; fortcraft_link.cpp then uses the read-back path.
#include "fortcraft_gpu.h"

bool FortCraftGpu_Init( void * ) { return false; }
void FortCraftGpu_Grab( int, int, int ) {}
void FortCraftGpu_Combine() {}
bool FortCraftGpu_TakeFinished( uint32_t * ) { return false; }
void FortCraftGpu_GetShared( uint64_t *pHandle0, uint64_t *pHandle1, uint32_t *pWidth, uint32_t *pHeight, uint32_t *pGeneration )
{
	*pHandle0 = *pHandle1 = 0;
	*pWidth = *pHeight = *pGeneration = 0;
}
const char *FortCraftGpu_Error() { return "not built on Linux (Direct3D sharing is Windows-only)"; }
const char *FortCraftGpu_HookInfo() { return "nothing"; }
void FortCraftGpu_GiveUp() {}
bool FortCraftGpu_Broken() { return false; }
void FortCraftGpu_Survey( void ( * )( const char * ) ) {}
#endif
