package scan3d;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Isola o contorno do objeto: uma silhueta fina (pixels 255 sobre fundo 0), não uma região preenchida.
 * <p>
 * Duas ideias, que vieram de comparar com o {@code out/} de 2018:
 * <ul>
 *   <li><b>Bordas do código antigo:</b> {@code Canny} direto do cinza (limiares 20 e 100) e dilatação 2x2. Elas
 *       fecham o contorno do objeto bem melhor que as de {@link MarkerDetector#edges}, que passam por
 *       {@code adaptiveThreshold} para favorecer os marcadores.</li>
 *   <li><b>Região do objeto:</b> o objeto fica em pé dentro do retângulo dos marcadores. Reprojeta-se esse retângulo
 *       (encolhido para excluir os marcadores e esticado para cima até {@link #MAX_HEIGHT_MM}) com a pose do quadro
 *       e só as bordas dentro dela são consideradas. Isso tira as linhas da borda da folha, o fundo e os marcadores,
 *       que se ligavam ao contorno do objeto e o contaminavam.</li>
 * </ul>
 * Entre as bordas da região, escolhe-se o componente de maior caixa envolvente (o contorno do objeto costuma sair
 * aberto, com área ~0, então a área do contorno não serve) e mantém-se só uma banda fina em volta dele.
 * <b>Limitação:</b> o objeto precisa caber dentro do retângulo dos marcadores e ter até {@code MAX_HEIGHT_MM} de altura.
 */
public final class ObjectContour {

    /** Margem, em mm, tirada de cada lado do retângulo dos marcadores (os marcadores têm ~20 mm e ficam nos cantos). */
    static final double MARGIN_MM = 25;
    /** Altura máxima do objeto considerada, em mm. */
    static final double MAX_HEIGHT_MM = 250;

    private static final double LOW_THRESHOLD = 20;
    private static final double HIGH_THRESHOLD = 100;
    private static final double MIN_BOX_AREA = 400;
    private static final int BAND = 4;

    /** Cantos do volume onde o objeto pode estar: retângulo encolhido em Z = 0 e o mesmo esticado para cima (Z < 0). */
    public static List<Point3> volumeCorners() {
        double x0 = MARGIN_MM, x1 = PoseEstimator.WIDTH_MM - MARGIN_MM;
        double y0 = MARGIN_MM, y1 = PoseEstimator.HEIGHT_MM - MARGIN_MM;
        List<Point3> c = new ArrayList<>();
        for (double z : new double[] {0, -MAX_HEIGHT_MM}) {
            c.add(new Point3(x0, y0, z));
            c.add(new Point3(x1, y0, z));
            c.add(new Point3(x1, y1, z));
            c.add(new Point3(x0, y1, z));
        }
        return c;
    }

    /**
     * @param bgr        foto colorida
     * @param volumeInImage os {@link #volumeCorners()} projetados na imagem com a pose do quadro
     */
    public Mat extract(Mat bgr, Point[] volumeInImage) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
        Mat raw = new Mat();
        Imgproc.Canny(gray, raw, LOW_THRESHOLD, HIGH_THRESHOLD);

        // região do objeto: casco convexo do volume projetado
        Mat region = Mat.zeros(raw.size(), CvType.CV_8UC1);
        MatOfPoint pts = new MatOfPoint();
        pts.fromArray(toInt(volumeInImage));
        MatOfInt hullIdx = new MatOfInt();
        Imgproc.convexHull(pts, hullIdx);
        Point[] all = pts.toArray();
        Point[] hull = new Point[(int) hullIdx.total()];
        for (int i = 0; i < hull.length; i++) hull[i] = all[hullIdx.toArray()[i]];
        Imgproc.fillConvexPoly(region, new MatOfPoint(hull), new Scalar(255));

        Mat rawIn = new Mat();
        Core.bitwise_and(raw, region, rawIn);
        Mat dilated = new Mat();
        Imgproc.dilate(rawIn, dilated, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2)));

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(dilated, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        MatOfPoint best = null;
        double bestBox = MIN_BOX_AREA;
        for (MatOfPoint c : contours) {
            double box = Imgproc.boundingRect(c).area();
            if (box > bestBox) {
                bestBox = box;
                best = c;
            }
        }

        Mat result = Mat.zeros(raw.size(), CvType.CV_8UC1);
        if (best == null) return result;
        Mat band = Mat.zeros(raw.size(), CvType.CV_8UC1);
        Imgproc.drawContours(band, List.of(best), -1, new Scalar(255), BAND);
        Core.bitwise_and(rawIn, band, result);
        return result;
    }

    private static Point[] toInt(Point[] p) {
        Point[] out = new Point[p.length];
        for (int i = 0; i < p.length; i++) out[i] = new Point(Math.round(p[i].x), Math.round(p[i].y));
        return out;
    }
}
