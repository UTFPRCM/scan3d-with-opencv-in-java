package scan3d;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

/**
 * Isola o contorno do objeto. O resultado é uma silhueta fina (pixels 255 sobre fundo 0), não uma região preenchida.
 * <p>
 * Baseado em {@code MainActivity.maskImage} (Example02), que escolhia o maior contorno da imagem de bordas.
 * Isso falhava quando o contorno do objeto saía aberto e um marcador (anel fechado) "vencia" em área.
 * Agora o candidato precisa respeitar a geometria da cena: o objeto fica no centro da folha, então seu
 * retângulo envolvente contém o centro dos 4 marcadores e não contém nenhum marcador.
 */
public final class ObjectContour {

    private static final double MIN_BOX_AREA = 2000;

    public Mat extract(Mat edges, MarkerDetector.MarkerSet markers) {
        // Sem fechamento morfológico: ele funde o contorno do objeto com as linhas da borda da folha.
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(edges.clone(), contours, new Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        Point[] m = markers.asArray();
        Point center = new Point((m[0].x + m[1].x + m[2].x + m[3].x) / 4, (m[0].y + m[1].y + m[2].y + m[3].y) / 4);

        // O contorno do objeto costuma sair aberto (área ~0), então ele é ranqueado pelo retângulo envolvente.
        MatOfPoint best = null;
        double bestBox = MIN_BOX_AREA;
        for (MatOfPoint c : contours) {
            Rect box = Imgproc.boundingRect(c);
            if (box.area() <= bestBox || !box.contains(center)) continue;
            boolean hitsMarker = false;
            for (Point p : m) {
                if (box.contains(p)) hitsMarker = true;
            }
            if (hitsMarker) continue;
            bestBox = box.area();
            best = c;
        }

        Mat result = Mat.zeros(edges.size(), CvType.CV_8UC1);
        if (best == null) return result;

        Mat band = Mat.zeros(edges.size(), CvType.CV_8UC1);
        List<MatOfPoint> one = List.of(best);
        Imgproc.drawContours(band, one, -1, new Scalar(255), 4);
        Imgproc.drawContours(band, one, -1, new Scalar(0), 1);
        Core.bitwise_and(edges, band, result);
        return result;
    }
}
