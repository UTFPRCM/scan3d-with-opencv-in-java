package scan3d;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lê a nuvem gravada por {@link PlyWriter} (PLY ASCII com x, y, z e, opcionalmente, frame e markers). */
public final class PlyReader {

    /** Nuvem em arrays paralelos (mais leve que uma lista de objetos para centenas de milhares de pontos). */
    public record Cloud(float[] x, float[] y, float[] z, int[] frame, int[] markers, String unit) {
        public int size() {
            return x.length;
        }
    }

    public static Cloud read(Path file) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file)) {
            if (!"ply".equals(r.readLine())) throw new IOException("Não é um arquivo PLY: " + file);
            List<String> props = new ArrayList<>();
            int count = -1;
            String unit = "";
            String line;
            while ((line = r.readLine()) != null && !line.equals("end_header")) {
                if (line.startsWith("comment ")) unit = line.substring(8).trim();
                String[] t = line.trim().split("\\s+");
                if (t[0].equals("format") && !t[1].equals("ascii")) throw new IOException("Só PLY ASCII é suportado");
                if (t[0].equals("element") && t[1].equals("vertex")) count = Integer.parseInt(t[2]);
                if (t[0].equals("property") && count >= 0) props.add(t[t.length - 1]);
            }
            if (count < 0) throw new IOException("PLY sem elemento 'vertex': " + file);
            int ix = props.indexOf("x"), iy = props.indexOf("y"), iz = props.indexOf("z");
            int iF = props.indexOf("frame"), iM = props.indexOf("markers");
            if (ix < 0 || iy < 0 || iz < 0) throw new IOException("PLY sem x, y, z: " + file);

            float[] x = new float[count], y = new float[count], z = new float[count];
            int[] frame = new int[count], markers = new int[count];
            for (int i = 0; i < count; i++) {
                line = r.readLine();
                if (line == null) throw new IOException("PLY truncado: esperado " + count + " pontos, lidos " + i);
                String[] t = line.trim().split("\\s+");
                x[i] = Float.parseFloat(t[ix]);
                y[i] = Float.parseFloat(t[iy]);
                z[i] = Float.parseFloat(t[iz]);
                frame[i] = iF >= 0 ? Integer.parseInt(t[iF]) : 0;
                markers[i] = iM >= 0 ? Integer.parseInt(t[iM]) : 4;
            }
            return new Cloud(x, y, z, frame, markers, unit);
        }
    }
}
