package scan3d;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

/**
 * Detecta o marcador planar: 2 triângulos + 2 quadrados nos cantos da folha A4.
 * Baseado no Example02 (limiarização adaptativa MEAN + Canny + dilatação + contornos), com classificação por
 * número de vértices e teste de aparência no lugar da razão de áreas, que rejeitava triângulos finos em perspectiva.
 */
public final class MarkerDetector {

    /** Forma detectada: centroide em pixels e raio aproximado (para descartar contornos duplicados). */
    public record Shape(Point center, double radius) {}

    /** Resultado bruto da detecção (pode ter qualquer quantidade de formas). */
    public record Detection(List<Shape> triangles, List<Shape> squares) {
        public boolean complete() {
            return triangles.size() == 2 && squares.size() == 2;
        }
    }

    /** Quatro centros na ordem do modelo 3D: tri0, sq0, tri1, sq1. */
    public record MarkerSet(Point tri0, Point sq0, Point tri1, Point sq1) {
        public Point[] asArray() {
            return new Point[] {tri0, sq0, tri1, sq1};
        }
    }

    /** Imagem de bordas usada tanto para achar os marcadores quanto o contorno do objeto. */
    public Mat edges(Mat bgr) {
        Mat gray = new Mat();
        Mat work = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.adaptiveThreshold(gray, work, 250, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 15, 5);
        Mat canny = new Mat();
        Imgproc.Canny(work, canny, 30, 120);
        Mat out = new Mat();
        Imgproc.dilate(canny, out, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 1)));
        return out;
    }

    /** Miolo do marcador precisa ser escuro e a vizinhança bem mais clara (preto sobre papel branco). */
    static final double MAX_INSIDE_GRAY = 110;
    static final double MIN_CONTRAST = 60;
    static final int CLOSE_KERNEL = 5;

    public Detection detect(Mat bgr, Mat edges) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);

        List<Shape> triangles = new ArrayList<>();
        List<Shape> squares = new ArrayList<>();
        List<MatOfPoint> contours = new ArrayList<>();
        // A borda de triângulos finos costuma sair com falhas de 1-2 px; sem fechá-las, o contorno vira uma linha
        // de área ~0. O fechamento morfológico só é usado aqui, não na extração do contorno do objeto.
        Mat closed = new Mat();
        Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(CLOSE_KERNEL, CLOSE_KERNEL)));
        Imgproc.findContours(closed, contours, new Mat(), Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE);

        for (MatOfPoint contour : contours) {
            MatOfPoint2f c2f = new MatOfPoint2f(contour.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            MatOfPoint2f approx2f = new MatOfPoint2f();
            Imgproc.approxPolyDP(c2f, approx2f, 0.04 * peri, true);
            double areaApprox = Math.abs(Imgproc.contourArea(approx2f));
            // contornos fechados pequenos ou grandes demais são ruído (mesmas faixas do Example02)
            if (areaApprox <= 200 || areaApprox >= 8000) continue;

            // triângulo = polígono convexo de 3 vértices; quadrado (visto em perspectiva) = convexo de 4 vértices
            MatOfPoint approx = new MatOfPoint(approx2f.toArray());
            if (!Imgproc.isContourConvex(approx)) continue;
            int n = (int) approx2f.total();
            if (n != 3 && n != 4) continue;
            if (!looksLikeMarker(gray, approx)) continue;

            Shape shape = new Shape(centroid(approx2f, approx2f.toArray()), enclosingRadius(c2f));
            addUnique(n == 3 ? triangles : squares, shape);
        }
        return new Detection(triangles, squares);
    }

    /** True se o polígono é escuro por dentro e nitidamente mais claro logo ao redor. */
    private static boolean looksLikeMarker(Mat gray, MatOfPoint poly) {
        Mat inside = Mat.zeros(gray.size(), CvType.CV_8UC1);
        Imgproc.fillPoly(inside, List.of(poly), new Scalar(255));
        Mat grown = new Mat();
        Imgproc.dilate(inside, grown, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(17, 17)));
        Mat ring = new Mat();
        Core.subtract(grown, inside, ring);
        double in = Core.mean(gray, inside).val[0];
        double out = Core.mean(gray, ring).val[0];
        return in < MAX_INSIDE_GRAY && out - in > MIN_CONTRAST;
    }

    /**
     * Ordena os centros de forma determinística, sem depender da ordem dos contornos nem da rotação da folha.
     * <p>
     * No modelo, os quadrados estão em +X em relação aos triângulos, e tri1/sq1 estão em +Y de tri0/sq0
     * (X para a direita, Y para baixo, como na imagem). Como a câmera vê a folha de cima, a orientação
     * é preservada: o produto vetorial (eixo triângulo→quadrado) x (eixo entre as duas formas iguais)
     * deve ser positivo. Isso escolhe qual é a "0" e qual é a "1" em qualquer rotação no plano.
     */
    public MarkerSet order(Detection d) {
        if (!d.complete()) throw new IllegalArgumentException("São necessários exatamente 2 triângulos e 2 quadrados");
        Point mt = mid(d.triangles().get(0).center(), d.triangles().get(1).center());
        Point ms = mid(d.squares().get(0).center(), d.squares().get(1).center());
        Point x = new Point(ms.x - mt.x, ms.y - mt.y);
        Point[] t = sortAlong(x, d.triangles().get(0).center(), d.triangles().get(1).center());
        Point[] s = sortAlong(x, d.squares().get(0).center(), d.squares().get(1).center());
        return new MarkerSet(t[0], s[0], t[1], s[1]);
    }

    private static Point[] sortAlong(Point x, Point a, Point b) {
        double cross = x.x * (b.y - a.y) - x.y * (b.x - a.x);
        return cross > 0 ? new Point[] {a, b} : new Point[] {b, a};
    }

    private static void addUnique(List<Shape> list, Shape s) {
        // contornos externo e interno do mesmo marcador caem no mesmo lugar: guarda só um
        for (Shape o : list) {
            if (dist(o.center(), s.center()) < Math.max(o.radius(), s.radius())) return;
        }
        list.add(s);
    }

    private static Point centroid(MatOfPoint2f poly, Point[] fallback) {
        Moments m = Imgproc.moments(poly);
        if (Math.abs(m.m00) > 1e-9) return new Point(m.m10 / m.m00, m.m01 / m.m00);
        double sx = 0, sy = 0;
        for (Point q : fallback) {
            sx += q.x;
            sy += q.y;
        }
        return new Point(sx / fallback.length, sy / fallback.length);
    }

    private static double enclosingRadius(MatOfPoint2f c2f) {
        float[] r = new float[1];
        Imgproc.minEnclosingCircle(c2f, new Point(), r);
        return r[0];
    }

    private static Point mid(Point a, Point b) {
        return new Point((a.x + b.x) / 2, (a.y + b.y) / 2);
    }

    private static double dist(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
