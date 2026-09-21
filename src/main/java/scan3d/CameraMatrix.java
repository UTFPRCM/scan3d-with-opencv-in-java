package scan3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;

/**
 * Matriz intrínseca K da câmera.
 *
 * Formato do arquivo (uma linha, separada por vírgulas):
 *   fx,0,cx,0,fy,cy,0,0,1              (formato original, 9 valores)
 *   fx,0,cx,0,fy,cy,0,0,1,largura,altura   (11 valores; guarda a resolução da calibração)
 */
public record CameraMatrix(double fx, double fy, double cx, double cy, Size calibSize) {

    public static CameraMatrix load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        String last = null;
        for (String line : lines) {
            if (!line.isBlank()) last = line.trim();
        }
        if (last == null) throw new IOException("Arquivo de câmera vazio: " + file);
        double[] v = Arrays.stream(last.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .mapToDouble(Double::parseDouble).toArray();
        if (v.length != 9 && v.length != 11) {
            throw new IOException("Formato esperado: fx,0,cx,0,fy,cy,0,0,1[,largura,altura] em " + file);
        }
        Size size = v.length == 11 ? new Size(v[9], v[10]) : null;
        return new CameraMatrix(v[0], v[4], v[2], v[5], size);
    }

    public void save(Path file) throws IOException {
        String line = String.format(Locale.ROOT, "%s,0.0,%s,0.0,%s,%s,0.0,0.0,1.0", fx, cx, fy, cy);
        if (calibSize != null) {
            line += String.format(Locale.ROOT, ",%d,%d", (long) calibSize.width, (long) calibSize.height);
        }
        Files.writeString(file, line + System.lineSeparator());
    }

    /** Aproximação sem calibração: f = largura da imagem (~53° de campo de visão horizontal), ponto principal no centro. */
    public static CameraMatrix approximate(Size image) {
        return new CameraMatrix(image.width, image.width, image.width / 2.0, image.height / 2.0, image);
    }

    /**
     * Ajusta K para a resolução da imagem. Só é possível se a resolução da calibração é conhecida
     * e a proporção é a mesma; caso contrário devolve this (e {@link #check} avisa).
     */
    public CameraMatrix forImage(Size image) {
        if (calibSize == null) return this;
        double sx = image.width / calibSize.width;
        double sy = image.height / calibSize.height;
        if (Math.abs(sx - sy) / sx > 0.02) return this;
        return new CameraMatrix(fx * sx, fy * sy, cx * sx, cy * sy, image);
    }

    /** Devolve um aviso se K não parece compatível com a resolução da imagem; null caso contrário. */
    public String check(Size image) {
        double w = calibSize != null ? calibSize.width : 2 * cx;
        double h = calibSize != null ? calibSize.height : 2 * cy;
        boolean off = Math.abs(w - image.width) / image.width > 0.1 || Math.abs(h - image.height) / image.height > 0.1;
        if (!off) return null;
        return String.format(Locale.ROOT,
                "K parece calibrada para ~%.0fx%.0f, mas a imagem tem %.0fx%.0f; a pose ficará imprecisa. "
                        + "Recalibre (comando 'calibrate') ou use --approx-camera.",
                w, h, image.width, image.height);
    }

    public Mat toMat() {
        Mat k = Mat.eye(3, 3, CvType.CV_64F);
        k.put(0, 0, fx);
        k.put(0, 2, cx);
        k.put(1, 1, fy);
        k.put(1, 2, cy);
        return k;
    }
}
