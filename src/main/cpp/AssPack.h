// Pure-C frame packer behind the JNI atlas render entry point (AssKt.c).
// Kept free of JNI/Android includes so desktop test harnesses can compile the
// exact shipped packing/composite logic against a host libass build.
#ifndef NUVIO_ASS_PACK_H
#define NUVIO_ASS_PACK_H

#include <stddef.h>
#include <stdint.h>

#include "ass/ass.h"

// Hard cap on atlas pages. 4 pages of a GL-max texture covers the worst real
// frame measured for ordinary typesetting.
#define ASS_PACK_MAX_PAGES 4

#define ASS_PACK_MODE_ATLAS 0
#define ASS_PACK_MODE_COMPOSITE 1

typedef struct {
  int mode;
  int atlasWidth;
  int quadCount;
  int truncated;
  int requiredPages;
  int pageCount;
  int totalTiles;
  long long srcPixels;
  int pageHeights[ASS_PACK_MAX_PAGES];
  int pageQuads[ASS_PACK_MAX_PAGES];
} AssPackResult;

int ass_pack_frame(
    ASS_Image* image, uint8_t* atlasPixels, size_t atlasCap, int atlasMaxW, int atlasMaxH, float* vertices,
    size_t vertexCap, AssPackResult* out);

#endif  // NUVIO_ASS_PACK_H
