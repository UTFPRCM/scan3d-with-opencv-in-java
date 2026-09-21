package scan3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;

/**
 * Cilindro sintético de raio e altura conhecidos, com o centro fora do centro da folha, visto por uma câmera que
 * dá meia volta mudando de distância. A nuvem deve devolver o mesmo raio em todos os ângulos.
 */
class LaminaCloudBuilderTest {

    private static final double RADIUS = 32, HEIGHT = 100;
    private static final double[] OFFSET = {8, -5};                        // centro do cilindro, em mm, relativo ao centro da folha
    private static final double[] O = {PoseEstimator.WIDTH_MM / 2, PoseEstimator.HEIGHT_MM / 2, 0};

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private record Frame(Pose pose, Mat mask) {}

    @Test
    void cylinderRadiusIsRecoveredAtEveryAngleAndDistance() {
        CameraMatrix cam = CameraMatrix.approximate(new org.opencv.core.Size(800, 480));
        PoseEstimator est = new PoseEstimator(cam.toMat());
        LaminaCloudBuilder builder = new LaminaCloudBuilder(est);

        int frames = 0;
        for (double az = -80; az <= 80; az += 2) {
            double dist = 375 + 75 * Math.sin(Math.toRadians(az * 3)); // 300 a 450 mm: a escala da imagem varia ~1,5x
            Frame f = frame(cam, est, az, dist, 20);
            assertTrue(builder.add(frames, 4, f.mask(), f.pose()) > 0, "quadro sem pontos em " + az + "°");
            frames++;
        }
        List<PlyWriter.FramePoints> cloud = builder.finish();

        double sref = builder.referenceScale();
        assertTrue(sref > 0);
        int checked = 0;
        for (PlyWriter.FramePoints fp : cloud) {
            for (double[] p : fp.points()) {
                double h = p[2] / sref;
                if (h < 0.15 * HEIGHT || h > 0.85 * HEIGHT) continue; // longe das elipses da tampa e da base
                assertEquals(RADIUS, Math.hypot(p[0], p[1]) / sref, 2.0, "raio a " + Math.round(h) + " mm");
                checked++;
            }
        }
        assertTrue(checked > 1000, "pontos verificados: " + checked);

        double[] off = builder.objectOffset();
        assertEquals(Math.hypot(OFFSET[0], OFFSET[1]), Math.hypot(off[0], off[1]) / sref, 1.5,
                "deslocamento do centro estimado");
        // 160° de azimute (-80° a +80°): cada plano vale para o ângulo dele e o oposto, então ~2x, com 1° de resolução
        assertEquals(2 * (160 + 2), builder.coverageDegrees(), 6, "cobertura angular");
    }

    /** Sem a recentragem, um objeto fora do eixo deformaria a nuvem; com ela, o raio não depende do ângulo. */
    @Test
    void radiusDoesNotDependOnAzimuth() {
        CameraMatrix cam = CameraMatrix.approximate(new org.opencv.core.Size(800, 480));
        PoseEstimator est = new PoseEstimator(cam.toMat());
        LaminaCloudBuilder builder = new LaminaCloudBuilder(est);
        int n = 0;
        for (double az = -80; az <= 80; az += 4) {
            Frame f = frame(cam, est, az, 350, 15);
            builder.add(n++, 4, f.mask(), f.pose());
        }
        double sref = builder.referenceScale();
        List<Double> perFrameMedian = new ArrayList<>();
        for (PlyWriter.FramePoints fp : builder.finish()) {
            List<Double> r = new ArrayList<>();
            for (double[] p : fp.points()) {
                double h = p[2] / sref;
                if (h >= 20 && h <= 80) r.add(Math.hypot(p[0], p[1]) / sref);
            }
            java.util.Collections.sort(r);
            perFrameMedian.add(r.get(r.size() / 2));
        }
        double min = java.util.Collections.min(perFrameMedian), max = java.util.Collections.max(perFrameMedian);
        assertTrue(max - min < 2.0, "raio por quadro varia de " + min + " a " + max);
    }

    // ------------------------------------------------------------------ cena sintética

    /** Câmera a {@code azDeg} de azimute, {@code dist} mm do centro da folha e {@code elevDeg} de elevação, olhando para ele. */
    private static Frame frame(CameraMatrix cam, PoseEstimator est, double azDeg, double dist, double elevDeg) {
        double az = Math.toRadians(azDeg), el = Math.toRadians(elevDeg);
        double[] c = {O[0] + dist * Math.cos(az) * Math.cos(el), O[1] + dist * Math.sin(az) * Math.cos(el),
                -dist * Math.sin(el)}; // Z do mundo aponta para a mesa: acima da folha é Z < 0
        double[] zc = norm(new double[] {O[0] - c[0], O[1] - c[1], O[2] - c[2]});
        double[] xc = norm(cross(new double[] {0, 0, 1}, zc));
        double[] yc = cross(zc, xc);
        Mat r = new Mat(3, 3, CvType.CV_64F);
        r.put(0, 0, xc[0], xc[1], xc[2], yc[0], yc[1], yc[2], zc[0], zc[1], zc[2]);
        Mat rvec = new Mat();
        Calib3d.Rodrigues(r, rvec);
        Mat tvec = new Mat(3, 1, CvType.CV_64F);
        tvec.put(0, 0, -(xc[0] * c[0] + xc[1] * c[1] + xc[2] * c[2]), -(yc[0] * c[0] + yc[1] * c[1] + yc[2] * c[2]),
                -(zc[0] * c[0] + zc[1] * c[1] + zc[2] * c[2]));

        // a pose vem do mesmo caminho do programa: marcadores projetados -> solvePnP
        Point[] m = project(cam, rvec, tvec, PoseEstimator.MODEL);
        Pose pose = est.estimate(new MarkerDetector.MarkerSet(m[0], m[1], m[2], m[3]));

        // silhueta do cilindro: as duas arestas verticais tangentes e as elipses da tampa e da base
        double[] axis = {O[0] + OFFSET[0], O[1] + OFFSET[1]};
        double azC = Math.atan2(c[1] - axis[1], c[0] - axis[0]);
        double nx = -Math.sin(azC), ny = Math.cos(azC);
        List<Point3> pts = new ArrayList<>();
        for (double z = 0; z <= HEIGHT; z += 0.4) {
            for (int s : new int[] {-1, 1}) pts.add(new Point3(axis[0] + s * RADIUS * nx, axis[1] + s * RADIUS * ny, -z));
        }
        for (double a = 0; a < 2 * Math.PI; a += 0.01) {
            for (double z : new double[] {0, HEIGHT}) {
                pts.add(new Point3(axis[0] + RADIUS * Math.cos(a), axis[1] + RADIUS * Math.sin(a), -z));
            }
        }
        Mat mask = Mat.zeros(480, 800, CvType.CV_8UC1);
        for (Point p : project(cam, rvec, tvec, pts)) {
            int x = (int) Math.round(p.x), y = (int) Math.round(p.y);
            if (x >= 0 && x < 800 && y >= 0 && y < 480) mask.put(y, x, 255);
        }
        return new Frame(pose, mask);
    }

    private static Point[] project(CameraMatrix cam, Mat rvec, Mat tvec, List<Point3> world) {
        MatOfPoint3f obj = new MatOfPoint3f();
        obj.fromList(world);
        MatOfPoint2f out = new MatOfPoint2f();
        Calib3d.projectPoints(obj, rvec, tvec, cam.toMat(), new MatOfDouble(Mat.zeros(4, 1, CvType.CV_64F)), out);
        return out.toArray();
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / l, v[1] / l, v[2] / l};
    }
}
