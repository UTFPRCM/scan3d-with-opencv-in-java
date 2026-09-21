package scan3d;

import java.util.List;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;

/** Estima a pose da câmera a partir dos 4 centros dos marcadores (solvePnP). */
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

    private final Mat cameraMatrix;
    private final MatOfDouble distCoeffs = new MatOfDouble(Mat.zeros(4, 1, CvType.CV_64FC1));

    public PoseEstimator(Mat cameraMatrix) {
        this.cameraMatrix = cameraMatrix;
    }

    public Pose estimate(MarkerDetector.MarkerSet markers) {
        MatOfPoint3f objectPoints = new MatOfPoint3f();
        objectPoints.fromList(MODEL);
        MatOfPoint2f imagePoints = new MatOfPoint2f(markers.asArray());

        Mat rvec = new Mat();
        Mat tvec = new Mat();
        Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvec, tvec);

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

        return new Pose(rvec, tvec, rot, Math.toDegrees(rx), Math.toDegrees(ry), Math.toDegrees(rz),
                reprojectionError(objectPoints, markers, rvec, tvec));
    }

    /** Projeta pontos 3D do mundo para a imagem com a pose dada (para desenhar eixos, verificar erro etc.). */
    public Point[] project(List<Point3> world, Pose pose) {
        MatOfPoint3f obj = new MatOfPoint3f();
        obj.fromList(world);
        MatOfPoint2f out = new MatOfPoint2f();
        Calib3d.projectPoints(obj, pose.rvec(), pose.tvec(), cameraMatrix, distCoeffs, out);
        return out.toArray();
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
