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
        rvec.put(0, 0, 0.9, 0.2, 0.1); // câmera acima da folha, inclinada
        Mat tvec = new Mat(3, 1, org.opencv.core.CvType.CV_64F);
        tvec.put(0, 0, -60, -40, 450);

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

    /** Esconde cada marcador por vez: a pose de 3 marcadores deve bater com a de 4 e ser marcada como confiança menor. */
    @Test
    void threeMarkersRecoverPoseFromNeighbourReference() {
        CameraMatrix cam = CameraMatrix.approximate(new Size(800, 480));
        PoseEstimator est = new PoseEstimator(cam.toMat());
        Pose truth = est.estimate(projectModel(cam, 0.9, 0.2, 0.1, -60, -40, 450));
        Pose neighbour = est.estimate(projectModel(cam, 0.92, 0.21, 0.1, -56, -38, 455)); // câmera ~7 mm adiante
        Point[] p = projectModel(cam, 0.9, 0.2, 0.1, -60, -40, 450).asArray();
        assertTrue(PoseEstimator.cameraCenter(truth.rotation(), truth.tvec())[2] < 0, "câmera deve estar acima da folha");

        for (int miss = 0; miss < 4; miss++) {
            List<MarkerDetector.Shape> tri = new java.util.ArrayList<>();
            List<MarkerDetector.Shape> sq = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if (i == miss) continue;
                (i % 2 == 0 ? tri : sq).add(new MarkerDetector.Shape(p[i], 10));
            }
            java.util.Collections.reverse(tri); // a ordem de entrada não pode importar
            var det = new MarkerDetector.Detection(tri, sq);
            assertTrue(det.partial());
            assertFalse(det.complete());

            var rec = est.estimateFromThree(det, neighbour, 60, 30);
            assertTrue(rec != null, "sem pose com o marcador " + miss + " escondido");
            assertEquals(3, rec.pose().markers());
            assertTrue(rec.pose().lowConfidence());
            assertNull(rec.used()[miss], "o marcador escondido não pode constar como visto");
            for (int i = 0; i < 3; i++) {
                assertEquals(truth.tvec().get(i, 0)[0], rec.pose().tvec().get(i, 0)[0], 1.0, "tvec[" + i + "] miss=" + miss);
            }
        }
    }

    /** Uma referência muito distante não pode ser usada para "validar" uma pose de 3 marcadores. */
    @Test
    void threeMarkersRejectedWhenReferenceIsFarAway() {
        CameraMatrix cam = CameraMatrix.approximate(new Size(800, 480));
        PoseEstimator est = new PoseEstimator(cam.toMat());
        Pose far = est.estimate(projectModel(cam, 0.9, 0.2, 0.1, -60, -40, 900)); // bem mais longe
        Point[] p = projectModel(cam, 0.9, 0.2, 0.1, -60, -40, 450).asArray();
        var det = new MarkerDetector.Detection(
                List.of(new MarkerDetector.Shape(p[0], 10)),
                List.of(new MarkerDetector.Shape(p[1], 10), new MarkerDetector.Shape(p[3], 10)));
        assertNull(est.estimateFromThree(det, far, 60, 30));
    }

    /** Duas formas ou menos, ou 4 marcadores, não são um caso de 3 marcadores. */
    @Test
    void partialDetectionRequiresExactlyThreeMarkers() {
        var s = new MarkerDetector.Shape(new Point(0, 0), 5);
        assertFalse(new MarkerDetector.Detection(List.of(s), List.of(s)).partial());
        assertFalse(new MarkerDetector.Detection(List.of(s, s), List.of(s, s)).partial());
        assertTrue(new MarkerDetector.Detection(List.of(s, s), List.of(s)).partial());
        assertTrue(new MarkerDetector.Detection(List.of(s), List.of(s, s)).partial());
        assertFalse(new MarkerDetector.Detection(List.of(), List.of(s, s, s)).partial());
    }

    private static MarkerDetector.MarkerSet projectModel(CameraMatrix cam, double rx, double ry, double rz, double tx,
            double ty, double tz) {
        Mat rvec = new Mat(3, 1, org.opencv.core.CvType.CV_64F);
        rvec.put(0, 0, rx, ry, rz);
        Mat tvec = new Mat(3, 1, org.opencv.core.CvType.CV_64F);
        tvec.put(0, 0, tx, ty, tz);
        var obj = new org.opencv.core.MatOfPoint3f();
        obj.fromList(PoseEstimator.MODEL);
        var proj = new org.opencv.core.MatOfPoint2f();
        Calib3d.projectPoints(obj, rvec, tvec, cam.toMat(), new MatOfDouble(Mat.zeros(4, 1, org.opencv.core.CvType.CV_64F)), proj);
        Point[] q = proj.toArray();
        return new MarkerDetector.MarkerSet(q[0], q[1], q[2], q[3]);
    }

    /**
     * O contorno do objeto deve reproduzir o do {@code out/} de 2018 (referência histórica): quase todos os pixels
     * do {@code out/} precisam estar no resultado, e o resultado não pode ser vazio nem virar a imagem inteira.
     */
    @Test
    void objectContourReproducesHistoricalOutput() {
        Mat bgr = Imgcodecs.imread(SAMPLE.toString());
        MarkerDetector d = new MarkerDetector();
        var markers = d.order(d.detect(bgr, d.edges(bgr)));
        PoseEstimator est = new PoseEstimator(CameraMatrix.approximate(bgr.size()).toMat());
        Pose pose = est.estimate(markers);

        Mat mask = new ObjectContour().extract(bgr, est.project(ObjectContour.volumeCorners(), pose));

        Mat reference = Imgcodecs.imread("out/Image-2018-05-24 12_29_42_071.matMask.jpg", Imgcodecs.IMREAD_GRAYSCALE);
        assertFalse(reference.empty(), "referência histórica não encontrada em out/");
        Mat ref = new Mat();
        org.opencv.imgproc.Imgproc.threshold(reference, ref, 127, 255, org.opencv.imgproc.Imgproc.THRESH_BINARY);
        Mat near = new Mat();
        org.opencv.imgproc.Imgproc.dilate(mask, near, org.opencv.imgproc.Imgproc.getStructuringElement(
                org.opencv.imgproc.Imgproc.MORPH_ELLIPSE, new Size(7, 7)));
        Mat hit = new Mat();
        org.opencv.core.Core.bitwise_and(ref, near, hit);

        double covered = org.opencv.core.Core.countNonZero(hit) / (double) org.opencv.core.Core.countNonZero(ref);
        int px = org.opencv.core.Core.countNonZero(mask);
        assertTrue(covered >= 0.9, "só " + Math.round(covered * 100) + "% do contorno de 2018 foi reproduzido");
        assertTrue(px > 300 && px < 4000, "quantidade de pixels de contorno fora do esperado: " + px);
    }
}
