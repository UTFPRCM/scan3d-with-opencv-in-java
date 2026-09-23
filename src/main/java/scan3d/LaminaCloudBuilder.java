package scan3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Point3;

/**
 * Nuvem de pontos por lâminas: cada contorno vira uma lâmina 2D <b>em pé</b>, no plano vertical que passa pelo eixo
 * de giro (Z, pelo centro da folha), girada pelo ângulo da câmera naquele quadro. O conjunto das lâminas forma o
 * objeto 3D.
 * <ul>
 *   <li><b>Ângulo.</b> É o azimute da câmera em volta do centro da folha, tirado da pose, relativo ao primeiro quadro
 *       (que fica em 0°). Os passos reais são irregulares; não se supõe 1 ou 2° fixos.</li>
 *   <li><b>Escala linear.</b> A escala da imagem muda com a distância da câmera (nas fotos de exemplo, quase 2x) e
 *       com a altura (pontos mais altos ficam mais perto da câmera). Cada pixel do contorno é lançado como um raio e
 *       intersectado com o plano da lâmina, o que trata perspectiva e inclinação. A unidade da nuvem é o "pixel de
 *       referência": um pixel do primeiro quadro na altura do eixo. É só uma mudança de unidade constante (não se
 *       mostra mm), então a escala fica linear: objetos do mesmo tamanho têm o mesmo tamanho em qualquer quadro.</li>
 *   <li><b>Origem.</b> O eixo é onde o centro da folha se projeta; a altura 0 é o plano da folha; Z cresce para cima.</li>
 *   <li><b>Pontos.</b> Só a silhueta externa: para cada altura, os extremos esquerdo e direito do contorno. Isso ignora
 *       as bordas internas (rótulo etc.) e é exato para corpos de revolução, onde a meia-largura é o raio. O topo e
 *       a base ficam de fora de propósito: com a câmera acima do objeto as elipses da tampa e da base ficariam
 *       deslocadas em altura. Picos isolados (por exemplo, a borda da folha que encosta no objeto) são descartados.</li>
 *   <li><b>Centro do objeto.</b> As lâminas supõem o objeto sobre o eixo, mas ele costuma estar alguns mm fora do centro
 *       da folha, o que deforma a forma (o raio deixa de ser o mesmo em todos os ângulos). Como o centro do objeto
 *       é fixo, a média dos extremos esquerdo e direito em cada quadro vale {@code e·t} (o deslocamento {@code e}
 *       projetado na direção {@code t} da lâmina). O {@code e} é estimado por mínimos quadrados robustos sobre todos
 *       os quadros e o objeto é recentrado no eixo em {@link #finish()}.</li>
 *   <li><b>Volta fechada.</b> Cada lâmina é bilateral (esquerda e direita do eixo) e a vista do lado oposto cai no
 *       mesmo plano com a silhueta espelhada; portanto, azimutes cobrindo A graus fecham cerca de 2·A graus em
 *       volta do eixo (ver {@link #coverageDegrees()}). Não é preciso duplicar lâminas.</li>
 * </ul>
 * Para objetos que não são de revolução é uma aproximação: o ponto da borda é posto no plano do eixo, e faces planas
 * ficam "estufadas". A versão geral seria o <i>visual hull</i> (interseção das silhuetas).
 * <p>
 * Uso: {@link #add} para cada quadro, na ordem (o primeiro define o ângulo 0 e a escala de referência), e depois
 * {@link #finish()} para obter os pontos já recentrados.
 */
public final class LaminaCloudBuilder {

    /** Meia janela, em linhas, do filtro de picos. */
    private static final int SPIKE_WINDOW = 20;
    /** Desvio mínimo, em pixels de referência, para um extremo ser considerado pico. */
    private static final double SPIKE_MIN_PX = 8;

    private final PoseEstimator estimator;
    private final double[] center = {PoseEstimator.WIDTH_MM / 2, PoseEstimator.HEIGHT_MM / 2, 0};

    private boolean first = true;
    private double azimuth0;
    private double refScale;                 // pixels da imagem por mm da folha, no primeiro quadro
    private final boolean[] planeHit = new boolean[180];
    private final List<Lamina> laminas = new ArrayList<>();
    private double offsetX, offsetY;         // centro estimado do objeto, em pixels de referência

    /** Uma lâmina: por altura, os extremos esquerdo e direito do contorno. */
    private record Lamina(int frame, int markers, double theta, double[] heights, double[] left, double[] right,
            double mid) {}

    public LaminaCloudBuilder(PoseEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * Guarda a lâmina de um quadro.
     *
     * @return quantos pontos o quadro contribui (0 se a pose for degenerada ou o contorno estiver vazio)
     */
    public int add(int frame, int markers, Mat contourMask, Pose pose) {
        double[] cam = PoseEstimator.cameraCenter(pose.rotation(), pose.tvec());
        double azimuth = Math.atan2(cam[1] - center[1], cam[0] - center[0]);
        // plano da lâmina: contém o eixo Z e é perpendicular à direção horizontal de visada
        double nx = Math.cos(azimuth), ny = Math.sin(azimuth);      // normal do plano (do eixo para a câmera)
        double tx = -ny, ty = nx;                                    // direção horizontal dentro do plano

        if (first) {
            // escala de referência: pixels da imagem por mm da folha, na altura do eixo, no primeiro quadro
            Point[] p = estimator.project(List.of(new Point3(center[0], center[1], center[2]),
                    new Point3(center[0] + 100 * tx, center[1] + 100 * ty, 0)), pose);
            double px = Math.hypot(p[1].x - p[0].x, p[1].y - p[0].y) / 100;
            if (px < 1e-9) return 0;
            first = false;
            azimuth0 = azimuth;
            refScale = px;
        }
        double theta = azimuth - azimuth0;
        markPlane(theta);

        // Cada pixel do contorno é lançado como um raio a partir da câmera e intersectado com o plano da lâmina:
        // isso resolve perspectiva e inclinação de uma vez. O resultado (mm) vai para pixels de referência, uma
        // mudança de unidade constante para todos os quadros, logo a escala fica linear entre eles.
        double[] k = estimator.intrinsics();
        double[][] r = new double[3][3];
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) r[a][b] = pose.rotation().get(a, b)[0];
        }
        double nDotOC = nx * (center[0] - cam[0]) + ny * (center[1] - cam[1]);
        int rows = contourMask.rows(), cols = contourMask.cols();
        byte[] data = new byte[rows * cols];
        contourMask.get(0, 0, data);
        TreeMap<Integer, double[]> extremes = new TreeMap<>();
        for (int y = 0; y < rows; y++) {
            double vy = (y - k[3]) / k[1];
            for (int x = 0; x < cols; x++) {
                if ((data[y * cols + x] & 0xFF) == 0) continue;
                double vx = (x - k[2]) / k[0];
                // direção do raio no mundo: Rᵀ · (vx, vy, 1)
                double dx = r[0][0] * vx + r[1][0] * vy + r[2][0];
                double dy = r[0][1] * vx + r[1][1] * vy + r[2][1];
                double dz = r[0][2] * vx + r[1][2] * vy + r[2][2];
                double nDotD = nx * dx + ny * dy;
                if (Math.abs(nDotD) < 1e-9) continue;               // raio paralelo ao plano
                double lambda = nDotOC / nDotD;
                if (lambda <= 0) continue;                           // atrás da câmera
                double px = cam[0] + lambda * dx - center[0], py = cam[1] + lambda * dy - center[1];
                double d = (px * tx + py * ty) * refScale;           // deslocamento horizontal no plano
                double h = -(cam[2] + lambda * dz) * refScale;       // altura acima da folha (Z do mundo aponta para a mesa)
                double[] e = extremes.computeIfAbsent((int) Math.round(h), key -> new double[] {d, d});
                e[0] = Math.min(e[0], d);
                e[1] = Math.max(e[1], d);
            }
        }
        removeSpikes(extremes);

        if (extremes.isEmpty()) return 0;
        int n = extremes.size();
        double[] hs = new double[n], left = new double[n], right = new double[n], mids = new double[n];
        int i = 0;
        for (var e : extremes.entrySet()) {
            hs[i] = e.getKey();
            left[i] = e.getValue()[0];
            right[i] = e.getValue()[1];
            mids[i] = (left[i] + right[i]) / 2;
            i++;
        }
        laminas.add(new Lamina(frame, markers, theta, hs, left, right, median(mids)));
        return 2 * n;
    }

    /**
     * Estima o centro do objeto, recentra-o no eixo e devolve os pontos por quadro (x, y, z em pixels de referência).
     */
    public List<PlyWriter.FramePoints> finish() {
        estimateOffset();
        removeGlobalOutliers();
        List<PlyWriter.FramePoints> out = new ArrayList<>();
        for (Lamina l : laminas) {
            double ct = Math.cos(l.theta()), st = Math.sin(l.theta());
            double shift = offsetX * ct + offsetY * st;   // e·t
            List<double[]> pts = new ArrayList<>(l.heights().length * 2);
            for (int i = 0; i < l.heights().length; i++) {
                for (double d : new double[] {l.left()[i] - shift, l.right()[i] - shift}) {
                    pts.add(new double[] {d * ct, d * st, l.heights()[i]});
                }
            }
            out.add(new PlyWriter.FramePoints(l.frame(), l.markers(), pts));
        }
        return out;
    }

    /** Centro estimado do objeto em relação ao eixo, em pixels de referência {x, y}; {0, 0} se não foi possível estimar. */
    public double[] objectOffset() {
        return new double[] {offsetX, offsetY};
    }

    /** Mínimos quadrados robustos (reponderação iterativa) de {@code mid_i = e·t_i}, com t_i = (cos θ_i, sin θ_i). */
    private void estimateOffset() {
        offsetX = offsetY = 0;
        int n = laminas.size();
        if (n < 3) return;
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1);
        double ex = 0, ey = 0;
        for (int iter = 0; iter < 8; iter++) {
            double sxx = 0, sxy = 0, syy = 0, bx = 0, by = 0;
            for (int i = 0; i < n; i++) {
                double c = Math.cos(laminas.get(i).theta()), s = Math.sin(laminas.get(i).theta()), m = laminas.get(i).mid();
                sxx += w[i] * c * c;
                sxy += w[i] * c * s;
                syy += w[i] * s * s;
                bx += w[i] * c * m;
                by += w[i] * s * m;
            }
            double det = sxx * syy - sxy * sxy;
            // ângulos quase iguais (planos paralelos) não determinam o deslocamento
            if (det < 1e-3 * Math.max(1e-9, sxx + syy) * Math.max(1e-9, sxx + syy)) return;
            ex = (bx * syy - by * sxy) / det;
            ey = (by * sxx - bx * sxy) / det;
            double[] res = new double[n];
            for (int i = 0; i < n; i++) {
                double c = Math.cos(laminas.get(i).theta()), s = Math.sin(laminas.get(i).theta());
                res[i] = Math.abs(laminas.get(i).mid() - (ex * c + ey * s));
            }
            double scale = Math.max(1.0, 1.4826 * median(res));
            for (int i = 0; i < n; i++) w[i] = res[i] <= 2 * scale ? 1 : (2 * scale) / res[i]; // Huber
        }
        offsetX = ex;
        offsetY = ey;
    }

    /** Pixels da imagem por mm da folha no primeiro quadro (a escala de referência); NaN antes do primeiro quadro. */
    public double referenceScale() {
        return first ? Double.NaN : refScale;
    }

    /**
     * Quantos graus em volta do eixo a nuvem cobre (0 a 360), com resolução de 1°. Cada lâmina é um plano, que cobre
     * o ângulo dela e o oposto; um plano em θ vale para θ e θ + 180°.
     */
    public double coverageDegrees() {
        int planes = 0;
        for (boolean b : planeHit) if (b) planes++;
        return 2.0 * planes;
    }

    /** Linha para o cabeçalho do PLY, com a unidade e a escala. */
    public String unitComment() {
        return String.format(Locale.ROOT,
                "unidade: pixels de referencia (pixels do primeiro quadro na altura do eixo; %.3f px por mm da folha); "
                        + "eixo Z para cima, altura 0 = plano da folha; quadro 0 = angulo 0; "
                        + "objeto recentrado no eixo (deslocamento estimado %.1f, %.1f)", refScale, offsetX, offsetY);
    }

    private void markPlane(double thetaRad) {
        int deg = (int) Math.floor(Math.toDegrees(thetaRad));
        for (int k = -1; k <= 1; k++) planeHit[Math.floorMod(deg + k, 180)] = true;
    }

    /** Descarta extremos muito distantes da mediana dos vizinhos (linhas próximas), por lado. */
    private static void removeSpikes(TreeMap<Integer, double[]> rows) {
        if (rows.size() < 5) return;
        List<Integer> keys = new ArrayList<>(rows.keySet());
        double[] lo = new double[keys.size()], hi = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            lo[i] = rows.get(keys.get(i))[0];
            hi[i] = rows.get(keys.get(i))[1];
        }
        List<Integer> drop = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            double[] nl = neighbours(keys, lo, i), nh = neighbours(keys, hi, i);
            double medLo = median(nl), medHi = median(nh);
            double tol = Math.max(SPIKE_MIN_PX, 0.15 * (medHi - medLo) / 2);
            if (Math.abs(lo[i] - medLo) > tol || Math.abs(hi[i] - medHi) > tol) drop.add(keys.get(i));
        }
        drop.forEach(rows::remove);
    }

    private static double[] neighbours(List<Integer> keys, double[] v, int i) {
        List<Double> out = new ArrayList<>();
        for (int j = i; j >= 0 && keys.get(i) - keys.get(j) <= SPIKE_WINDOW; j--) out.add(v[j]);
        for (int j = i + 1; j < keys.size() && keys.get(j) - keys.get(i) <= SPIKE_WINDOW; j++) out.add(v[j]);
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        return s[s.length / 2];
    }

    /**
     * Descarta linhas cujo raio ((direita-esquerda)/2) destoa muito da mediana de raio de <b>todas</b> as lâminas.
     * Complementa {@link #removeSpikes}, que só compara com uma janela de linhas vizinhas: quando o contorno "vaza"
     * além do objeto numa faixa inteira de altura (por exemplo, a borda de trás da folha encostando no objeto — ver
     * {@code ObjectContour} — capturada como parte do contorno), a contaminação pode ser uma fração grande de uma
     * lâmina específica, ou decair devagar até se confundir com o normal; nos dois casos, a mediana daquela lâmina
     * sozinha não destoa o bastante para acusar nada, mas a mediana de todas as lâminas sim (o objeto real é bem
     * mais uniforme que isso).
     */
    private void removeGlobalOutliers() {
        List<Double> radii = new ArrayList<>();
        for (Lamina l : laminas) {
            for (int i = 0; i < l.heights().length; i++) radii.add((l.right()[i] - l.left()[i]) / 2);
        }
        if (radii.size() < 20) return;
        double[] arr = radii.stream().mapToDouble(Double::doubleValue).toArray();
        double med = median(arr);
        double tol = med + 6 * 1.4826 * mad(arr, med);
        for (int li = 0; li < laminas.size(); li++) {
            Lamina l = laminas.get(li);
            int n = l.heights().length;
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((l.right()[i] - l.left()[i]) / 2 <= tol) keep.add(i);
            }
            if (keep.size() == n) continue;
            double[] hs = new double[keep.size()], left = new double[keep.size()], right = new double[keep.size()];
            for (int k = 0; k < keep.size(); k++) {
                int i = keep.get(k);
                hs[k] = l.heights()[i];
                left[k] = l.left()[i];
                right[k] = l.right()[i];
            }
            laminas.set(li, new Lamina(l.frame(), l.markers(), l.theta(), hs, left, right, l.mid()));
        }
    }

    private static double mad(double[] v, double med) {
        double[] dev = new double[v.length];
        for (int i = 0; i < v.length; i++) dev[i] = Math.abs(v[i] - med);
        return median(dev);
    }
}
