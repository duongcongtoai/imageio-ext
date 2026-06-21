package it.geosolutions.imageio.tiff;

import it.geosolutions.imageioimpl.plugins.cog.CogTileInfo;
import it.geosolutions.imageioimpl.plugins.cog.TileRange;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CogTileSortingTest {

    @Test
    public void testTileSortingByOffset() {
        CogTileInfo info = new CogTileInfo(16384);
        
        // Add tiles out of byte-offset order
        info.addTileRange(3, 50000, 100);
        info.addTileRange(1, 20000, 100);
        info.addTileRange(2, 30000, 100);
        
        List<TileRange> sortedRanges = new ArrayList<>(info.getTileRanges().values());
        
        // Ensure initial order is by tileIndex (TreeMap behavior)
        Assert.assertEquals(-100, sortedRanges.get(0).getIndex()); // Header
        Assert.assertEquals(1, sortedRanges.get(1).getIndex());
        Assert.assertEquals(2, sortedRanges.get(2).getIndex());
        Assert.assertEquals(3, sortedRanges.get(3).getIndex());
        
        // Sort by byte offset
        sortedRanges.sort(Comparator.comparingLong(TileRange::getStart));
        
        // Verify sorted order
        Assert.assertEquals(-100, sortedRanges.get(0).getIndex()); // Header starts at 0
        Assert.assertEquals(1, sortedRanges.get(1).getIndex()); // offset 20000
        Assert.assertEquals(2, sortedRanges.get(2).getIndex()); // offset 30000
        Assert.assertEquals(3, sortedRanges.get(3).getIndex()); // offset 50000
    }
}
