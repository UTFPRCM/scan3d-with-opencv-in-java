package scan3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point3;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Calibração da câmera com tabuleiro de xadrez (portado de CalibChessBoard.java~).
 * Fotografe o tabuleiro impresso em várias posições, na MESMA resolução das fotos do objeto.
 */
public final class Calibrator {

    private final Size pattern;      // cantos internos (colunas x linhas)
    private final double squareMm;

    public Calibrator(Size pattern, double squareMm) {
        this.pattern = pattern;
        this.squareMm = squareMm;
    }

    public CameraMatrix calibrate(Path dir) throws IOException {
        List<Mat> objectPoints = new ArrayList<>();
        List<Mat> imagePoints = new ArrayList<>();
        Size imageSize = null;
        int seen = 0;

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().toLowerCase().matches(".*\\.(jpe?g|png)")).sorted().toList();
        }
        for (Path f : files) {
            Mat bgr = Imgcodecs.imread(f.toString(), Imgcodecs.IMREAD_COLOR);
            if (bgr.empty()) continue;
            seen++;
            if (imageSize == null) imageSize = bgr.size();
            else if (!imageSize.equals(bgr.size())) {
                System.out.println("Ignorada (resolução diferente): " + f.getFileName());
                continue;
            }
            Mat gray = new Mat();
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            MatOfPoint2f corners = new MatOfPoint2f();
            boolean found = Calib3d.findChessboardCorners(gray, pattern, corners,
                    Calib3d.CALIB_CB_ADAPTIVE_THRESH | Calib3d.CALIB_CB_NORMALIZE_IMAGE);
            if (!found) {
                System.out.println("Tabuleiro não encontrado: " + f.getFileName());
                continue;
            }
            Imgproc.cornerSubPix(gray, corners, new Size(5, 5), new Size(-1, -1),
                    new TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 40, 0.001));
            objectPoints.add(boardPoints());
            imagePoints.add(corners);
        }
        if (objectPoints.size() < 5) {
            throw new IOException("Poucas imagens válidas (" + objectPoints.size() + " de " + seen
                    + "); use pelo menos 5, idealmente 15+, com o tabuleiro em posições e inclinações variadas.");
        }

        Mat k = Mat.eye(3, 3, CvType.CV_64F);
        Mat dist = Mat.zeros(8, 1, CvType.CV_64F);
        List<Mat> rvecs = new ArrayList<>();
        List<Mat> tvecs = new ArrayList<>();
        int flags = Calib3d.CALIB_ZERO_TANGENT_DIST | Calib3d.CALIB_FIX_K4 | Calib3d.CALIB_FIX_K5;
        double rms = Calib3d.calibrateCamera(objectPoints, imagePoints, imageSize, k, dist, rvecs, tvecs, flags);

        System.out.printf("Calibração com %d imagens, erro RMS = %.3f px%n", objectPoints.size(), rms);
        System.out.println("distCoeffs (ainda não usados pelo pipeline) = " + dist.t().dump());
        return new CameraMatrix(k.get(0, 0)[0], k.get(1, 1)[0], k.get(0, 2)[0], k.get(1, 2)[0], imageSize);
    }

    private MatOfPoint3f boardPoints() {
        List<Point3> pts = new ArrayList<>();
        for (int i = 0; i < (int) pattern.height; i++) {
            for (int j = 0; j < (int) pattern.width; j++) {
                pts.add(new Point3(j * squareMm, i * squareMm, 0));
            }
        }
        MatOfPoint3f m = new MatOfPoint3f();
        m.fromList(pts);
        return m;
    }
}
