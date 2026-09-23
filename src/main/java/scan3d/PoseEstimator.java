package scan3d;

import java.util.ArrayList;
import java.util.List;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;

/** Estima a pose da câmera a partir dos centros dos marcadores (solvePnP com 4; P3P com 3). */
public final class PoseEstimator {

    /** Larguras do marcador em mm (centro a centro). Medido no gabarito impresso: ≈185 x 160. */
    public static final double WIDTH_MM = 187.0;
    public static final double HEIGHT_MM = 161.0;

    /** Pontos 3D do modelo, na ordem tri0, sq0, tri1, sq1; folha em Z = 0. */
    public static final List<Point3> MODEL = List.of(
            new Point3(0, 0, 0),
            new Point3(WIDTH_MM, 0, 0),
            new Point3(0, HEIGHT_MM, 0),
            new Point3(WIDTH_MM, HEIGHT_MM, 0));

    /**
     * Pose recuperada com 3 marcadores.
     *
     * @param used       os 4 pontos do modelo na imagem; o marcador que faltou é null
     * @param refDistMm  distância (mm) entre a câmera desta pose e a da pose de referência usada para desempatar
     * @param refRotDeg  diferença de rotação (graus) para a pose de referência
     */
    public record Recovered(Pose pose, Point[] used, double refDistMm, double refRotDeg) {}

    private final Mat cameraMatrix;
    private final MatOfDouble distCoeffs = new MatOfDouble(Mat.zeros(4, 1, CvType.CV_64FC1));

    public PoseEstimator(Mat cameraMatrix) {
        this.cameraMatrix = cameraMatrix;
    }

    public Pose estimate(MarkerDetector.MarkerSet markers) {
        return estimate(markers, false);
    }

    /**
     * @param lowConfidence true se um dos 4 marcadores veio de {@link MarkerDetector#reclassify}: a posição ainda
     *                      é usada no solvePnP, mas o resultado é marcado como menos confiável (ver {@link Pose}).
     */
    public Pose estimate(MarkerDetector.MarkerSet markers, boolean lowConfidence) {
        MatOfPoint3f objectPoints = new MatOfPoint3f();
        objectPoints.fromList(MODEL);
        MatOfPoint2f imagePoints = new MatOfPoint2f(markers.asArray());

        Mat rvec = new Mat();
        Mat tvec = new Mat();
        Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvec, tvec);
        return toPose(rvec, tvec, reprojectionError(objectPoints, markers, rvec, tvec), 4, lowConfidence);
    }

    /**
     * Estima a pose com apenas 3 marcadores.
     * <p>
     * Com 3 pontos há duas ambiguidades: (1) qual dos marcadores do tipo que aparece uma vez única é ele
     * (o "0" ou o "1"), e (2) o P3P devolve até 4 soluções. As duas são resolvidas testando todas as hipóteses
     * e ficando com a pose mais próxima da {@code reference} (um quadro vizinho com 4 marcadores).
     * Como 3 pontos são ajustados exatamente, não há erro de reprojeção para conferir: o resultado só é aceito
     * se ficar a no máximo {@code maxJumpMm} / {@code maxRotDeg} da referência.
     *
     * @return a pose, ou null se não for 3 marcadores, se não houver solução válida ou se ela destoar da referência
     */
    public Recovered estimateFromThree(MarkerDetector.Detection d, Pose reference, double maxJumpMm, double maxRotDeg) {
        if (!d.partial()) return null;
        boolean missingTriangle = d.triangles().size() == 1;

        // Os dois marcadores do mesmo tipo são ordenados pela orientação; falta decidir só o "sozinho".
        Point[] pair;         // pontos do tipo que aparece duas vezes, já ordenados
        Point single;         // o que aparece uma vez
        int[] pairIds;        // ids do modelo para `pair`
        int[] singleHyp;      // ids possíveis para `single`
        if (missingTriangle) {
            single = d.triangles().get(0).center();
            Point a = d.squares().get(0).center(), b = d.squares().get(1).center();
            Point x = new Point((a.x + b.x) / 2 - single.x, (a.y + b.y) / 2 - single.y);
            pair = MarkerDetector.sortAlong(x, a, b);
            pairIds = new int[] {1, 3};
            singleHyp = new int[] {0, 2};
        } else {
            single = d.squares().get(0).center();
            Point a = d.triangles().get(0).center(), b = d.triangles().get(1).center();
            Point x = new Point(single.x - (a.x + b.x) / 2, single.y - (a.y + b.y) / 2);
            pair = MarkerDetector.sortAlong(x, a, b);
            pairIds = new int[] {0, 2};
            singleHyp = new int[] {1, 3};
        }

        double[] refCenter = cameraCenter(reference.rotation(), reference.tvec());
        Recovered best = null;
        double bestScore = Double.MAX_VALUE;
        for (int hyp : singleHyp) {
            int[] ids = missingTriangle ? new int[] {hyp, pairIds[0], pairIds[1]} : new int[] {pairIds[0], pairIds[1], hyp};
            Point[] pts = missingTriangle ? new Point[] {single, pair[0], pair[1]} : new Point[] {pair[0], pair[1], single};

            MatOfPoint3f obj = new MatOfPoint3f();
            obj.fromList(List.of(MODEL.get(ids[0]), MODEL.get(ids[1]), MODEL.get(ids[2])));
            List<Mat> rvecs = new ArrayList<>();
            List<Mat> tvecs = new ArrayList<>();
            int n = Calib3d.solveP3P(obj, new MatOfPoint2f(pts), cameraMatrix, distCoeffs, rvecs, tvecs,
                    Calib3d.SOLVEPNP_AP3P);
            for (int i = 0; i < n; i++) {
                Mat rot = new Mat();
                Calib3d.Rodrigues(rvecs.get(i), rot);
                double[] c = cameraCenter(rot, tvecs.get(i));
                // fisicamente válida: folha à frente da câmera e câmera acima dela (Z do mundo aponta para a mesa)
                if (tvecs.get(i).get(2, 0)[0] <= 0 || c[2] >= 0) continue;
                double dist = Math.sqrt(Math.pow(c[0] - refCenter[0], 2) + Math.pow(c[1] - refCenter[1], 2)
                        + Math.pow(c[2] - refCenter[2], 2));
                double rotDeg = rotationDifferenceDeg(rot, reference.rotation());
                // desempata pela posição E pela rotação: só a posição deixa passar hipóteses com a câmera no lugar
                // certo mas virada para outro lado (a folha, vista de perto, muda pouco a posição da câmera entre
                // soluções do P3P mas pode mudar bastante a rotação). A escala (mm por grau) é a mesma proporção dos
                // limites normais de aceitação (60 mm / 30° a cada quadro de distância).
                double score = dist + rotDeg * 2.0;
                if (score >= bestScore) continue;
                Point[] used = new Point[4];
                for (int k = 0; k < 3; k++) used[ids[k]] = pts[k];
                bestScore = score;
                best = new Recovered(toPose(rvecs.get(i), tvecs.get(i), Double.NaN, 3, true), used, dist, rotDeg);
            }
        }
        if (best == null || best.refDistMm() > maxJumpMm || best.refRotDeg() > maxRotDeg) return null;
        return best;
    }

    /** Projeta pontos 3D do mundo para a imagem com a pose dada (para desenhar eixos, verificar erro etc.). */
    public Point[] project(List<Point3> world, Pose pose) {
        MatOfPoint3f obj = new MatOfPoint3f();
        obj.fromList(world);
        MatOfPoint2f out = new MatOfPoint2f();
        Calib3d.projectPoints(obj, pose.rvec(), pose.tvec(), cameraMatrix, distCoeffs, out);
        return out.toArray();
    }

    /** Parâmetros intrínsecos {fx, fy, cx, cy} em pixels. */
    public double[] intrinsics() {
        return new double[] {cameraMatrix.get(0, 0)[0], cameraMatrix.get(1, 1)[0], cameraMatrix.get(0, 2)[0],
                cameraMatrix.get(1, 2)[0]};
    }

    /** Posição da câmera no mundo (mm): C = -Rᵀ·t. */
    static double[] cameraCenter(Mat rotation, Mat tvec) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = 0;
            for (int j = 0; j < 3; j++) s -= rotation.get(j, i)[0] * tvec.get(j, 0)[0];
            c[i] = s;
        }
        return c;
    }

    static double rotationDifferenceDeg(Mat a, Mat b) {
        Mat m = new Mat();
        Core.gemm(a, b, 1, new Mat(), 0, m, Core.GEMM_2_T);
        double trace = m.get(0, 0)[0] + m.get(1, 1)[0] + m.get(2, 2)[0];
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (trace - 1) / 2))));
    }

    private static Pose toPose(Mat rvec, Mat tvec, double reprojError, int markers, boolean lowConfidence) {
        Mat rot = new Mat();
        Calib3d.Rodrigues(rvec, rot);
        double m00 = rot.get(0, 0)[0], m10 = rot.get(1, 0)[0];
        double m11 = rot.get(1, 1)[0], m12 = rot.get(1, 2)[0];
        double m20 = rot.get(2, 0)[0], m21 = rot.get(2, 1)[0], m22 = rot.get(2, 2)[0];
        double sy = Math.sqrt(m00 * m00 + m10 * m10);
        double rx, ry = Math.atan2(-m20, sy), rz;
        if (sy >= 1e-6) {
            rx = Math.atan2(m21, m22);
            rz = Math.atan2(m10, m00);
        } else { // gimbal lock
            rx = Math.atan2(-m12, m11);
            rz = 0;
        }
        return new Pose(rvec, tvec, rot, Math.toDegrees(rx), Math.toDegrees(ry), Math.toDegrees(rz), reprojError,
                markers, lowConfidence);
    }

    private double reprojectionError(MatOfPoint3f obj, MarkerDetector.MarkerSet markers, Mat rvec, Mat tvec) {
        MatOfPoint2f proj = new MatOfPoint2f();
        Calib3d.projectPoints(obj, rvec, tvec, cameraMatrix, distCoeffs, proj);
        Point[] a = proj.toArray();
        Point[] b = markers.asArray();
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double dx = a[i].x - b[i].x, dy = a[i].y - b[i].y;
            sum += dx * dx + dy * dy;
        }
        return Math.sqrt(sum / a.length);
    }
}
