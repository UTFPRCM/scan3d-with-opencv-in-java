package scan3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;

class MarkerAndPoseTest {

    private static final Path SAMPLE = Path.of("in/800x480 com objeto/Image-2018-05-24 12_29_42_071.jpg");

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    /** A ordem tri0, sq0, tri1, sq1 não pode depender da rotação da folha na imagem. */
    @Test
    void orderingIsStableUnderInPlaneRotation() {
        double[][] model = {{0, 0}, {187, 0}, {0, 161}, {187, 161}}; // tri0, sq0, tri1, sq1
        for (int deg = 0; deg < 360; deg += 15) {
            double a = Math.toRadians(deg);
            Point[] img = new Point[4];
            for (int i = 0; i < 4; i++) {
                double x = model[i][0], y = model[i][1];
                img[i] = new Point(400 + x * Math.cos(a) - y * Math.sin(a), 240 + x * Math.sin(a) + y * Math.cos(a));
            }
            // entrega as formas embaralhadas: o detector não garante ordem
            var det = new MarkerDetector.Detection(
                    List.of(new MarkerDetector.Shape(img[2], 10), new MarkerDetector.Shape(img[0], 10)),
                    List.of(new MarkerDetector.Shape(img[3], 10), new MarkerDetector.Shape(img[1], 10)));
            var ordered = new MarkerDetector().order(det);
            assertEquals(img[0], ordered.tri0(), "tri0 a " + deg + "°");
            assertEquals(img[1], ordered.sq0(), "sq0 a " + deg + "°");
            assertEquals(img[2], ordered.tri1(), "tri1 a " + deg + "°");
            assertEquals(img[3], ordered.sq1(), "sq1 a " + deg + "°");
        }
    }

    /** Projeta o modelo com uma pose conhecida e confere se o solvePnP a recupera. */
    @Test
    void poseIsRecoveredFromSyntheticProjection() {
        CameraMatrix cam = CameraMatrix.approximate(new Size(800, 480));
        Mat rvec = new Mat(3, 1, org.opencv.core.CvType.CV_64F);
        rvec.put(0, 0, 2.6, 0.2, 0.1); // ~olhando a folha de cima e de lado
        Mat tvec = new Mat(3, 1, org.opencv.core.CvType.CV_64F);
        tvec.put(0, 0, -90, -60, 420);

        var obj = new org.opencv.core.MatOfPoint3f();
        obj.fromList(PoseEstimator.MODEL);
        var proj = new org.opencv.core.MatOfPoint2f();
        Calib3d.projectPoints(obj, rvec, tvec, cam.toMat(), new MatOfDouble(Mat.zeros(4, 1, org.opencv.core.CvType.CV_64F)), proj);
        Point[] p = proj.toArray();

        Pose pose = new PoseEstimator(cam.toMat()).estimate(new MarkerDetector.MarkerSet(p[0], p[1], p[2], p[3]));
        for (int i = 0; i < 3; i++) {
            assertEquals(tvec.get(i, 0)[0], pose.tvec().get(i, 0)[0], 0.5, "tvec[" + i + "]");
        }
        assertTrue(pose.reprojError() < 0.01, "erro de reprojeção " + pose.reprojError());
    }

    /** Foto real com os quatro marcadores visíveis: deve achar 2 + 2 e ordenar como no gabarito. */
    @Test
    void detectsMarkersInSamplePhoto() {
        Mat bgr = Imgcodecs.imread(SAMPLE.toString());
        assertFalse(bgr.empty(), "foto de exemplo não encontrada: " + SAMPLE.toAbsolutePath());
        MarkerDetector d = new MarkerDetector();
        var det = d.detect(bgr, d.edges(bgr));
        assertEquals(2, det.triangles().size(), "triângulos");
        assertEquals(2, det.squares().size(), "quadrados");

        var m = d.order(det);
        assertTrue(m.tri0().y < m.tri1().y, "tri0 acima de tri1 nesta foto");
        assertTrue(m.sq0().y < m.sq1().y, "sq0 acima de sq1 nesta foto");
        assertTrue(m.sq0().x > m.tri0().x, "quadrados à direita dos triângulos nesta foto");
    }

    @Test
    void cameraFileRoundTripAndScaling(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("cam.txt");
        new CameraMatrix(400, 400, 175.5, 143.5, new Size(351, 287)).save(f);
        CameraMatrix c = CameraMatrix.load(f);
        assertEquals(new Size(351, 287), c.calibSize());

        CameraMatrix same = c.forImage(new Size(702, 574)); // mesma proporção: escala x2
        assertEquals(800, same.fx(), 1e-9);
        assertEquals(351, same.cx(), 1e-9);
        assertNull(same.check(new Size(702, 574)));

        assertTrue(c.check(new Size(800, 480)) != null, "800x480 tem proporção diferente: deve avisar");

        Files.writeString(f, "378.8463980526608,0.0,175.5,0.0,378.8463980526608,143.5,0.0,0.0,1.0,\n"); // formato original
        CameraMatrix legacy = CameraMatrix.load(f);
        assertEquals(175.5, legacy.cx());
        assertNull(legacy.calibSize());
        assertTrue(legacy.check(new Size(800, 480)) != null);
    }
}
