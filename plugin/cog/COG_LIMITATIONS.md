# COG Range Reading Limitations & Future Work

This document outlines the recent improvements made to Cloud Optimized GeoTIFF (COG) reading, the context behind the range merging strategy, current limitations, and future work required.

## Recent Changes Summary
The following key optimizations and features were implemented to support compressed COGs over high-latency networks:

1. **WebP COG Support:** Implemented WebP decompression to support reading COGs compressed with the WebP tag (`50001`).
   * *Files:* `plugin/tiff/src/main/java/it/geosolutions/imageioimpl/plugins/tiff/TIFFWebPDecompressor.java` (new decompressor), `plugin/tiff/src/main/java/it/geosolutions/imageioimpl/plugins/tiff/TIFFImageReader.java` (registered the decompressor), and `plugin/cog/cog-reader/pom.xml` (added `imageio-webp` dependency).
2. **Lazy Loading Fix for Tile Byte Counts:** Fixed `TIFFIFD` to support `TIFF_LAZY_LONG` and `TIFF_LAZY_LONG8`. This prevents `TAG_TILE_BYTE_COUNTS` from being dropped during lazy loading, which previously caused a severe bug where the reader fell back to requesting a 2MB uncompressed payload assumption.
   * *Files:* `plugin/tiff/src/main/java/it/geosolutions/imageioimpl/plugins/tiff/TIFFIFD.java` (bypassed `isDataTypeOK` strict check) and `plugin/tiff/src/main/java/it/geosolutions/imageio/plugins/tiff/TIFFField.java` (allowed lazy long types in constructor).
3. **Sorted Tile Offsets:** Updated stream readers to sort tiles by byte offset before requesting them. Compressed tiles (unlike raw TIFFs) have unpredictable sizes, and sorting prevents the `ContiguousRangeComposer` from dropping out-of-order tiles.
   * *Files:* `plugin/cog/cog-streams/src/main/java/it/geosolutions/imageioimpl/plugins/cog/CachingCogImageInputStream.java` and `plugin/cog/cog-streams/src/main/java/it/geosolutions/imageioimpl/plugins/cog/DefaultCogImageInputStream.java` (added `sortedRanges.sort(Comparator.comparingLong(TileRange::getStart))`).
4. **EOF Clamping:** Added logic to clamp HTTP range requests so they never exceed the actual file length, preventing `416 Range Not Satisfiable` errors.
   * *Files:* `plugin/cog/cog-commons/src/main/java/it/geosolutions/imageioimpl/plugins/cog/AbstractRangeReader.java` (updated `reconcileRanges()` to clamp against `fileLength`).
5. **GCS Cache Eviction Fix:** Fixed redundant Google Cloud Storage metadata API calls by updating the Guava `BlobCache` policy.
   * *Context:* To perform range reads, `GSRangeReader` must first fetch `Blob` metadata from GCS (`GET ... ?projection=full`). This object was previously cached using Guava's `.weakValues()`. Because the COG pipeline frequently drops strong references to the Blob, the Java Garbage Collector was instantly clearing the cache during Minor GC cycles. This forced GeoServer to re-fetch the exact same `?projection=full` metadata over the network for almost every tile request.
   * *Fix:* Replaced `.weakValues()` with `.expireAfterAccess(10, MINUTES)` and `.maximumSize(1000)`. The metadata is now securely held in memory via strong references for 10 minutes, eliminating redundant API calls while panning a map.
   * *Files:* `plugin/cog/cog-rangereader-gs/src/main/java/it/geosolutions/imageioimpl/plugins/cog/BlobCache.java`.

---

## Context: Why are there gaps, and why are they a problem?

When fetching COG tiles over a network (HTTP/S3/GCS), reading ranges requires balancing the cost of **Network Latency** (TCP handshakes, Time-To-First-Byte) against **Network Bandwidth** (downloading extra padding data).

We might expect tiles to be stored perfectly contiguous on disk (e.g., Tile 1 ends at byte `X`, Tile 2 starts at `X + 1`). If true, requesting a block of tiles would require just one single HTTP `Range` request. However, gaps frequently exist for two main reasons:

1. **Sparse Fetching:** A user zooming into a map only requests a subset of tiles (e.g., Tile 10 and Tile 40). Between these tiles are massive gaps containing the data for Tiles 11-39, which we do not need.
2. **Disk Padding & Framing Headers:** COG writers (like GDAL's `COG` driver) heavily pad and frame compressed tiles. For example, when generating a COG with internal WebP or JPEG compression, GDAL injects an 8-byte "Ghost Header" (a 4-byte mask + a 4-byte block size) immediately *before* the start of the compressed payload.
    *   `Tile 0` payload ends.
    *   `[8-byte GDAL frame header for Tile 1]` -> **This creates an 8-byte gap!**
    *   `Tile 1` payload starts.

Because the TIFF `TileOffsets` array points directly to the payload and ignores the 8-byte frame, the tiles appear mathematically disjointed.

### The 64KB Workaround
If we strictly enforced a 0-byte gap policy for HTTP requests, an 8-byte padding gap would force the reader to sever the connection and initiate a brand new HTTP request for every single tile. Fetching 50 tiles would mean 50 HTTP requests, destroying performance due to latency.

To solve this, `ContiguousRangeComposer` implements a **hardcoded 64KB (`65536` bytes) merge tolerance**. If the gap between two requested tiles is less than 64KB, it merges them into a single HTTP request, sacrificing a tiny bit of bandwidth to save massive amounts of latency.

---

## Current Limitations

### 1. Hardcoded Gap Tolerance
* **Limitation:** The 64KB tolerance is hardcoded in `plugin/cog/cog-commons/src/main/java/it/geosolutions/imageioimpl/plugins/cog/ContiguousRangeComposer.java`.
* **Impact:** High-latency connections (cross-region S3) might benefit from merging larger gaps (1MB+), whereas low-latency local storage (NVMe) would prefer zero tolerance to avoid reading useless padding. We lack the flexibility to tune this tradeoff.

### 2. Ignorance of Total Request Payload Size
* **Limitation:** The composer merges *any* sequence of tiles separated by <64KB, without enforcing a maximum single-request size limit.
* **Impact:** A massive bounding box query covering thousands of tiles could be merged into a single, colossal 500MB HTTP `Range` request. This can cause dropped connections, timeouts, or memory pressure inside the `java.io.InputStream` buffers.

### 3. Gap Data Waste
* **Limitation:** The composer merges gaps blindly.
* **Impact:** If a gap contains large metadata (like an Image File Directory or overview offsets), we waste network bandwidth fetching bytes that the `TIFFImageReader` will ultimately ignore.

---

## Problems to Tackle Next (Future Work)

1. **Configurable Merge Strategies:**
   * Replace the hardcoded `65536` with a dynamic parameter (e.g., `it.geosolutions.cog.range.merge.threshold`) tunable via System Properties or injected via `CogImageReadParam`.
   * Introduce a `max_request_size` limit to cap single HTTP requests at safer chunks (e.g., 16MB or 32MB).

2. **HTTP/2 Multiplexing or Async Fetching:**
   * If a request yields multiple disconnected ranges, investigate dispatching those HTTP Range requests concurrently using asynchronous I/O or HTTP/2 multiplexing, rather than blocking sequentially.

3. **True "Sparse" Memory Management:**
   * Implement a `SparseByteArray` or `CompositeInputStream` that maps logical TIFF file offsets to only the specifically requested chunks in memory. This would allow us to completely discard the padding bytes once downloaded, saving RAM.
