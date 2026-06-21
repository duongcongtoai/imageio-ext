package it.geosolutions.imageio.cog;

import it.geosolutions.imageioimpl.plugins.cog.ContiguousRangeComposer;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ContiguousRangeComposerTest {

    @Test
    public void testMergeSmallGaps() {
        long initialRangeStart = 0;
        long initialRangeEnd = 16383;

        ContiguousRangeComposer rangeBuilder = new ContiguousRangeComposer(initialRangeStart, initialRangeEnd);
        
        // This is within 64KB (65536) of 16383
        rangeBuilder.addTileRange(20000, 25000);
        
        List<long[]> ranges = new ArrayList<>(rangeBuilder.getRanges());
        Assert.assertEquals(1, ranges.size());
        Assert.assertEquals(0, ranges.get(0)[0]);
        Assert.assertEquals(25000, ranges.get(0)[1]);
        
        // This is exactly 64KB (65536) away from 25000
        rangeBuilder.addTileRange(25000 + 65536, 100000);
        ranges = new ArrayList<>(rangeBuilder.getRanges());
        Assert.assertEquals(1, ranges.size());
        Assert.assertEquals(0, ranges.get(0)[0]);
        Assert.assertEquals(100000, ranges.get(0)[1]);
        
        // This is > 64KB away from 100000
        rangeBuilder.addTileRange(165537, 200000);
        ranges = new ArrayList<>(rangeBuilder.getRanges());
        Assert.assertEquals(2, ranges.size());
    }
}
