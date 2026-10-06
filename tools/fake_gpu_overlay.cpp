// Test the Minecraft side of the GPU overlay without TF2: pretends to be TF2 for a few seconds,
// creates two shared Direct3D 9Ex textures like TF2 does, fills them with a test pattern (a red
// box bottom-left, a green bar in the middle, the rest transparent) and publishes them.
// Minecraft should log "GPU overlay ready" and "opened TF2's GPU overlay textures".
//
// Build (Developer Command Prompt): cl /nologo /EHsc /O2 tools\fake_gpu_overlay.cpp d3d9.lib user32.lib
// Run:   fake_gpu_overlay.exe [seconds]
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <d3d9.h>
#include <stdio.h>
#include <stdlib.h>
#include "../protocol/fortcraft_protocol.h"

namespace proto = fortcraft::proto;

int main( int argc, char **argv )
{
	int seconds = argc > 1 ? atoi( argv[ 1 ] ) : 10;
	HANDLE hMap = NULL;
	for ( int i = 0; i < 100 && !hMap; ++i, Sleep( 100 ) )
		hMap = OpenFileMappingW( FILE_MAP_ALL_ACCESS, FALSE, proto::kMappingName );
	if ( !hMap )
	{
		printf( "[gpu] no link from minecraft\n" );
		return 1;
	}
	unsigned char *shm = (unsigned char *)MapViewOfFile( hMap, FILE_MAP_ALL_ACCESS, 0, 0, proto::kMappingBytes );
	proto::Header &h = *(proto::Header *)shm;
	if ( h.magic != proto::kMagic || h.version != proto::kVersion )
	{
		printf( "[gpu] version mismatch: link %u, ours %u\n", h.version, proto::kVersion );
		return 1;
	}
	h.guestPid = GetCurrentProcessId();

	IDirect3D9Ex *d3d = NULL;
	Direct3DCreate9Ex( D3D_SDK_VERSION, &d3d );
	D3DPRESENT_PARAMETERS pp = {};
	pp.Windowed = TRUE;
	pp.SwapEffect = D3DSWAPEFFECT_DISCARD;
	pp.BackBufferWidth = pp.BackBufferHeight = 1;
	pp.hDeviceWindow = GetDesktopWindow();
	IDirect3DDevice9Ex *dev = NULL;
	HRESULT hr = d3d->CreateDeviceEx( D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, GetDesktopWindow(), D3DCREATE_SOFTWARE_VERTEXPROCESSING, &pp, NULL, &dev );
	if ( FAILED( hr ) )
	{
		printf( "[gpu] CreateDeviceEx failed 0x%08lX\n", hr );
		return 1;
	}

	const UINT W = 1280, H = 720;
	IDirect3DTexture9 *tex[ 2 ] = {};
	HANDLE shared[ 2 ] = {};
	for ( int i = 0; i < 2; ++i )
	{
		hr = dev->CreateTexture( W, H, 1, D3DUSAGE_RENDERTARGET, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, &tex[ i ], &shared[ i ] );
		if ( FAILED( hr ) )
		{
			printf( "[gpu] CreateTexture failed 0x%08lX\n", hr );
			return 1;
		}
		IDirect3DSurface9 *s = NULL;
		tex[ i ]->GetSurfaceLevel( 0, &s );
		dev->ColorFill( s, NULL, D3DCOLOR_ARGB( 0, 0, 0, 0 ) );
		RECT red = { 40, (LONG)H - 120, 240, (LONG)H - 40 };
		dev->ColorFill( s, &red, D3DCOLOR_ARGB( 255, 220, 40, 40 ) );
		RECT bar = { (LONG)W / 2 - 100, (LONG)H / 2 - 4, (LONG)W / 2 + 100, (LONG)H / 2 + 4 };
		dev->ColorFill( s, &bar, D3DCOLOR_ARGB( 255, 0, 255, 0 ) );
		s->Release();
	}
	IDirect3DQuery9 *q = NULL;
	dev->CreateQuery( D3DQUERYTYPE_EVENT, &q );
	q->Issue( D3DISSUE_END );
	while ( q->GetData( NULL, 0, D3DGETDATA_FLUSH ) == S_FALSE )
		Sleep( 1 );

	proto::OverlayGpu &g = *(proto::OverlayGpu *)( shm + proto::kOffOverlayGpu );
	g.handle[ 0 ] = (uint64_t)(uintptr_t)shared[ 0 ];
	g.handle[ 1 ] = (uint64_t)(uintptr_t)shared[ 1 ];
	g.width = W;
	g.height = H;
	g.generation = g.generation + 1;
	g.valid = 1;
	printf( "[gpu] shared textures %ux%u published\n", W, H );

	DWORD end = GetTickCount() + seconds * 1000;
	uint64_t frame = 0;
	while ( GetTickCount() < end )
	{
		h.guestFrame = ++frame;
		h.guestHeartbeatMs = GetTickCount64();
		g.front = (uint32_t)( frame & 1 );
		g.seq = g.seq + 1;
		Sleep( 8 );
	}
	g.valid = 0;
	printf( "[gpu] sent %llu frames\n", (unsigned long long)frame );
	return 0;
}
