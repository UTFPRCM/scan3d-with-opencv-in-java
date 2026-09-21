package scan3d;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * Converte os pixels do contorno de um quadro em pontos 3D.
 * <p>
 * Portado de {@code PointsObjectInFrame} (Example02), mantendo a formulação original:
 * <ol>
 *   <li>H = K · [r1 r2 t] (homografia plano da folha → imagem, pois Z = 0); inverte-se H;</li>
 *   <li>[a b c]ᵀ = H⁻¹ · [u v 1]ᵀ; usa-se raio = b/c (o X = a/c é calculado e descartado, como no original);</li>
 *   <li>coordenadas esféricas com θ (rz da pose) e φ (|ry| da pose):
 *       x = r·sinθ·sinφ, y = r·cosθ·sinφ, z = r·cosφ.</li>
 * </ol>
 * Correções em relação ao original: os ângulos são convertidos para radianos antes de sin/cos
 * (o original passava graus) e θ negativo soma 360° (o original subtraía).
 * A validade geométrica dessa fórmula ainda precisa ser confirmada em testes práticos.
 */
public final class PointCloudBuilder {

    private static final int MASK_THRESHOLD = 200;

    private final Mat cameraMatrix;

    public PointCloudBuilder(Mat cameraMatrix) {
        this.cameraMatrix = cameraMatrix;
    }

    /** @return lista de pontos {x, y, z}; vazia se a homografia não for invertível */
    public List<double[]> build(Mat contourMask, Pose pose) {
        List<double[]> points = new ArrayList<>();

        Mat rt = new Mat(3, 3, CvType.CV_64F);
        for (int i = 0; i < 3; i++) {
            rt.put(i, 0, pose.rotation().get(i, 0)[0]);
            rt.put(i, 1, pose.rotation().get(i, 1)[0]);
            rt.put(i, 2, pose.tvec().get(i, 0)[0]);
        }
        Mat h = new Mat();
        Core.gemm(cameraMatrix, rt, 1.0, new Mat(), 0.0, h);
        if (Math.abs(Core.determinant(h)) < 1e-12) return points;
        Mat inv = h.inv();
        double[] m = new double[9];
        inv.get(0, 0, m);

        double theta = Math.toRadians(pose.rzDeg() < 0 ? 360 + pose.rzDeg() : pose.rzDeg());
        double phi = Math.toRadians(Math.abs(pose.ryDeg()));
        double sinT = Math.sin(theta), cosT = Math.cos(theta), sinP = Math.sin(phi), cosP = Math.cos(phi);

        int rows = contourMask.rows(), cols = contourMask.cols();
        byte[] data = new byte[rows * cols];
        contourMask.get(0, 0, data);
        for (int j = 0; j < rows; j++) {
            for (int k = 0; k < cols; k++) {
                if ((data[j * cols + k] & 0xFF) <= MASK_THRESHOLD) continue;
                double b = m[3] * k + m[4] * j + m[5];
                double c = m[6] * k + m[7] * j + m[8];
                if (Math.abs(c) < 1e-12) continue;
                double raio = b / c;
                points.add(new double[] {raio * sinT * sinP, raio * cosT * sinP, raio * cosP});
            }
        }
        return points;
    }
}
