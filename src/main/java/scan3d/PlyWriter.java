package scan3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Grava a nuvem de pontos em PLY ASCII (abre no MeshLab, CloudCompare, Open3D...). A propriedade "frame" é o índice do quadro. */
public final class PlyWriter {

    public record FramePoints(int frame, List<double[]> points) {}

    public static void write(Path file, List<FramePoints> frames) throws IOException {
        long total = frames.stream().mapToLong(f -> f.points().size()).sum();
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("ply\nformat ascii 1.0\n");
            w.write("comment unidade: mm, plano da folha = Z 0\n");
            w.write("element vertex " + total + "\n");
            w.write("property float x\nproperty float y\nproperty float z\nproperty int frame\nend_header\n");
            for (FramePoints f : frames) {
                for (double[] p : f.points()) {
                    w.write(String.format(Locale.ROOT, "%.3f %.3f %.3f %d%n", p[0], p[1], p[2], f.frame()));
                }
            }
        }
    }
}
