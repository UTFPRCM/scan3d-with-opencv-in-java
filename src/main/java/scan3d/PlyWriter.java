package scan3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Grava a nuvem de pontos em PLY ASCII (abre no MeshLab, CloudCompare, Open3D...).
 * Propriedades extras: "frame" (índice do quadro) e "markers" (4 = confiança normal, 3 = confiança menor).
 */
public final class PlyWriter {

    public record FramePoints(int frame, int markers, List<double[]> points) {}

    public static void write(Path file, List<FramePoints> frames) throws IOException {
        write(file, frames, "unidade: mm, plano da folha = Z 0");
    }

    /** @param unitComment texto do comentário do cabeçalho com a unidade e a origem das coordenadas */
    public static void write(Path file, List<FramePoints> frames, String unitComment) throws IOException {
        long total = frames.stream().mapToLong(f -> f.points().size()).sum();
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("ply\nformat ascii 1.0\n");
            w.write("comment " + unitComment + "; markers 3 = pose com confianca menor\n");
            w.write("element vertex " + total + "\n");
            w.write("property float x\nproperty float y\nproperty float z\nproperty int frame\nproperty int markers\nend_header\n");
            for (FramePoints f : frames) {
                for (double[] p : f.points()) {
                    w.write(String.format(Locale.ROOT, "%.3f %.3f %.3f %d %d\n", p[0], p[1], p[2], f.frame(), f.markers()));
                }
            }
        }
    }
}
