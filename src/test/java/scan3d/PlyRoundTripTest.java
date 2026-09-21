package scan3d;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlyRoundTripTest {

    @Test
    void writtenCloudIsReadBackWithFrameAndConfidence(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("cloud.ply");
        PlyWriter.write(f, List.of(
                new PlyWriter.FramePoints(3, 4, List.of(new double[] {1.5, -2.25, 3}, new double[] {0, 0, 0})),
                new PlyWriter.FramePoints(7, 3, List.of(new double[] {10, 20, 30}))));
        PlyReader.Cloud c = PlyReader.read(f);
        assertEquals(3, c.size());
        assertEquals(-2.25f, c.y()[0], 1e-3);
        assertEquals(30f, c.z()[2], 1e-3);
        assertEquals(3, c.frame()[0]);
        assertEquals(7, c.frame()[2]);
        assertEquals(4, c.markers()[1]);
        assertEquals(3, c.markers()[2]);
    }
}
